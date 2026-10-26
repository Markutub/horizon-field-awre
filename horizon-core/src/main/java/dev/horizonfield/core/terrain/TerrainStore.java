package dev.horizonfield.core.terrain;

import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory chunk summaries plus a version-stamped LOD pyramid built from them on demand.
 *
 * Versions: every content change of a chunk takes the next value of a global monotone clock and stamps the chunk and all
 * its ancestors (maxLod+1 map writes). The version of a tile is therefore the newest change anywhere below it, it only
 * ever grows, and it is the AWRE tile revision. Version 0 means "no data". Re-putting identical content changes nothing.
 *
 * Reduction (level L-1 -> L, per output sample from its 2x2 valid inputs): top = rounded mean (default) or max;
 * material = most frequent (ties: lowest id); confidence = weakest of the contributing children; no valid input -> NO_DATA.
 * MEAN minimises RMS height error (the quantity the LOD error model assumes); MAX never loses peaks (silhouettes) but
 * biases high by roughly half the local relief per level. Which is better for the horizon is an open, measurable question.
 */
public final class TerrainStore {
    public enum Reduce { MEAN, MAX }

    /** What a full store does with a chunk at a NEW coordinate. */
    public enum FullPolicy {
        /** Refuse it (the store only ever grows to the cap; what was observed first stays). The default, and the historical behaviour. */
        REFUSE_NEW,
        /** Make room by removing the least recently observed chunks, so the store follows where the players are now. */
        EVICT_OLDEST
    }

    public record Versioned(LodTile tile, long version) { }
    private record Cached(long version, LodTile tile) { }

    private final int maxLod;
    private final Reduce reduce;
    private final Map<Long, ColumnTile> chunks = new ConcurrentHashMap<>();
    private final Map<LodKey, Long> versions = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong(0);
    private final AtomicLong rejected = new AtomicLong();       // new chunks refused because the store was full (see setMaxChunks)
    private volatile int maxChunks = Integer.MAX_VALUE;
    private volatile FullPolicy fullPolicy = FullPolicy.REFUSE_NEW;
    private final AtomicLong evicted = new AtomicLong();          // chunks removed to admit newer ones (see FullPolicy.EVICT_OLDEST)
    private final Object evictLock = new Object();
    private final AtomicLong columnBytes = new AtomicLong();   // approx heap held by the observed chunks (see ColumnTile#approxHeapBytes)
    private final Map<LodKey, Cached> cache;

    public TerrainStore(int maxLod, Reduce reduce, int cacheEntries) {
        if (maxLod < 0 || maxLod > 12 || cacheEntries < 16) throw new IllegalArgumentException("store params");
        this.maxLod = maxLod; this.reduce = reduce;
        this.cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<LodKey, Cached> e) { return size() > cacheEntries; }
        };
    }

    private static long ck(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    /** Measured whole-store heap per observed chunk (chunk summary + version index), synthetic terrain: 406-433 B; rounded up. An estimate for budgeting only. */
    public static final int BYTES_PER_CHUNK_ESTIMATE = 450;

    /**
     * Hard cap on the number of observed chunks (default: unlimited). When full, a chunk at a NEW coordinate is refused ({@link #put} returns 0 and
     * {@link #rejectedChunks()} counts it); chunks already stored still update normally, so nothing that is held is ever dropped. The cap is checked
     * without a lock, so concurrent writers may overshoot it by at most the number of writer threads.
     */
    public void setMaxChunks(int max) {
        if (max < 1) throw new IllegalArgumentException("maxChunks " + max);
        this.maxChunks = max;
    }
    public int maxChunks() { return maxChunks; }
    public long rejectedChunks() { return rejected.get(); }

    /** @return the new version, the existing one if the content is identical, or 0 if the chunk is new and the store is full (nothing stored) */
    public long put(ColumnTile t) {
        long k = ck(t.chunkX(), t.chunkZ());
        // Writers (put, evict) exclude each other: chunks/versions/columnBytes change together. Readers (version, tile) never take this lock.
        synchronized (evictLock) {
            ColumnTile old = chunks.get(k);
            if (old != null && old.sameContent(t)) {
                Long same = versions.get(new LodKey(0, t.chunkX(), t.chunkZ()));
                if (same != null) {
                    // Re-observing an unchanged chunk is a touch: under EVICT_OLDEST it keeps the chunk young (same content = same size, so no accounting change)
                    if (fullPolicy == FullPolicy.EVICT_OLDEST && t.observedAtMillis() > old.observedAtMillis()) chunks.replace(k, old, t);
                    return same;
                }
            }
            if (old == null && chunks.size() >= maxChunks) {
                if (fullPolicy == FullPolicy.REFUSE_NEW || !makeRoomFor(t)) { rejected.incrementAndGet(); return 0; }
            }
            long v = clock.incrementAndGet();
            ColumnTile prev = chunks.put(k, t);
            columnBytes.addAndGet(t.approxHeapBytes() - (prev == null ? 0 : prev.approxHeapBytes()));
            LodKey n = new LodKey(0, t.chunkX(), t.chunkZ());
            for (int l = 0; l <= maxLod; l++) { versions.merge(n, v, Math::max); if (l < maxLod) n = n.parent(); }
            return v;
        }
    }

    /**
     * What a full store does with a chunk at a new coordinate (default {@link FullPolicy#REFUSE_NEW}). With EVICT_OLDEST the removed chunks
     * are the least recently OBSERVED ones (observedAtMillis; a re-observation of unchanged content counts), a batch of about 1/64 of the cap
     * at a time so the O(n) scan is amortised. An incoming chunk older than everything held is refused instead of replacing newer data.
     * Clients keep the tiles they already hold; the coarse tiles above an evicted chunk are re-versioned (or removed when nothing is left
     * below them). Evicted chunks stay in the on-disk cache (it is not rewritten).
     */
    public void setFullPolicy(FullPolicy p) { this.fullPolicy = java.util.Objects.requireNonNull(p); }
    public FullPolicy fullPolicy() { return fullPolicy; }
    public long evictedChunks() { return evicted.get(); }

    /** @return true when there is room for {@code incoming} now */
    private boolean makeRoomFor(ColumnTile incoming) {
        synchronized (evictLock) {
            if (chunks.size() < maxChunks) return true;                       // another writer made room meanwhile
            int batch = Math.max(1, maxChunks / 64);
            // the {@code batch} oldest chunks: keep a max-heap of the oldest seen so far (ties broken by key, so the choice is deterministic)
            java.util.PriorityQueue<Map.Entry<Long, ColumnTile>> heap = new java.util.PriorityQueue<>(batch + 1, (a, b) -> {
                int c = Long.compare(b.getValue().observedAtMillis(), a.getValue().observedAtMillis());
                return c != 0 ? c : Long.compare(b.getKey(), a.getKey());
            });
            for (Map.Entry<Long, ColumnTile> e : chunks.entrySet()) {
                heap.add(new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
                if (heap.size() > batch) heap.poll();
            }
            long newestVictim = Long.MIN_VALUE, oldest = Long.MAX_VALUE;
            for (Map.Entry<Long, ColumnTile> e : heap) { newestVictim = Math.max(newestVictim, e.getValue().observedAtMillis()); oldest = Math.min(oldest, e.getValue().observedAtMillis()); }
            if (incoming.observedAtMillis() < oldest) return false;          // older than everything we would evict: not worth replacing newer data
            for (Map.Entry<Long, ColumnTile> e : heap) evict(e.getKey());
            return chunks.size() < maxChunks;
        }
    }

    /** Removes one chunk and repairs the version index above it. */
    private void evict(long k) {
        ColumnTile gone = chunks.remove(k);
        if (gone == null) return;
        columnBytes.addAndGet(-gone.approxHeapBytes());
        evicted.incrementAndGet();
        LodKey n = new LodKey(0, (int) (k >> 32), (int) k);
        versions.remove(n);
        synchronized (cache) { cache.remove(n); }
        long v = clock.incrementAndGet();
        for (int l = 1; l <= maxLod; l++) {
            n = n.parent();
            boolean anyBelow = false;
            for (LodKey c : n.children()) if (versions.containsKey(c)) { anyBelow = true; break; }
            if (anyBelow) versions.merge(n, v, Math::max); else versions.remove(n);   // content changed (one child lost) or nothing left below
            synchronized (cache) { cache.remove(n); }
        }
    }

    public int chunkCount() { return chunks.size(); }

    /** Approximate heap bytes held by the observed chunks themselves (excludes the version index and the tile cache). For /awre stats. */
    public long approxChunkBytes() { return columnBytes.get(); }
    public long version(LodKey k) { return k.lod() > maxLod ? 0 : versions.getOrDefault(k, 0L); }

    /** The tile and the version it was built at, as one consistent pair (null if there is no data below it). */
    public Versioned versioned(LodKey k) {
        for (int attempt = 0; attempt < 3; attempt++) {
            long v = version(k);
            if (v == 0) return null;
            LodTile t = build(k, v);
            if (version(k) == v) return new Versioned(t, v);   // no change raced with the build
        }
        long v = version(k);
        return v == 0 ? null : new Versioned(build(k, v), v);
    }

    public LodTile tile(LodKey k) { Versioned v = versioned(k); return v == null ? null : v.tile(); }

    private LodTile build(LodKey k, long v) {
        synchronized (cache) { Cached c = cache.get(k); if (c != null && c.version == v) return c.tile; }
        LodTile t = k.lod() == 0 ? fromChunk(k) : combine(k);
        synchronized (cache) { cache.put(k, new Cached(v, t)); }
        return t;
    }

    private LodTile fromChunk(LodKey k) {
        ColumnTile c = chunks.get(ck(k.tx(), k.tz()));
        if (c == null) return empty(k);
        return new LodTile(k, c.topArray(), c.materialArray(), c.confidence());
    }

    private static LodTile empty(LodKey k) {
        short[] top = new short[LodTile.AREA];
        java.util.Arrays.fill(top, LodTile.NO_DATA);
        return new LodTile(k, top, new short[LodTile.AREA], Confidence.UNKNOWN);
    }

    private LodTile combine(LodKey k) {
        LodKey[] ch = k.children();   // order: (x,z) (x+1,z) (x,z+1) (x+1,z+1)
        LodTile[] kids = new LodTile[4];
        Confidence conf = null;
        for (int i = 0; i < 4; i++) {
            Versioned v = versioned(ch[i]);
            kids[i] = v == null ? null : v.tile();
            if (kids[i] != null) conf = conf == null ? kids[i].confidence() : Confidence.weakest(conf, kids[i].confidence());
        }
        short[] top = new short[LodTile.AREA], mat = new short[LodTile.AREA];
        for (int oz = 0; oz < 16; oz++) {
            for (int ox = 0; ox < 16; ox++) {
                LodTile kid = kids[(oz >= 8 ? 2 : 0) + (ox >= 8 ? 1 : 0)];
                int o = oz * 16 + ox;
                if (kid == null) { top[o] = LodTile.NO_DATA; continue; }
                int bx = (ox & 7) * 2, bz = (oz & 7) * 2;
                int n = 0, sum = 0, max = Integer.MIN_VALUE;
                Map<Integer, Integer> counts = new HashMap<>(4);
                for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                    int t = kid.topAt(bx + dx, bz + dz);
                    if (t == LodTile.NO_DATA) continue;
                    n++; sum += t; max = Math.max(max, t);
                    counts.merge(kid.materialAt(bx + dx, bz + dz), 1, Integer::sum);
                }
                if (n == 0) { top[o] = LodTile.NO_DATA; continue; }
                int h = reduce == Reduce.MAX ? max : Math.floorDiv(2 * sum + n, 2 * n);
                top[o] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, h));
                int bestM = 0, bestC = -1;
                for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                    if (e.getValue() > bestC || (e.getValue() == bestC && e.getKey() < bestM)) { bestC = e.getValue(); bestM = e.getKey(); }
                }
                mat[o] = (short) bestM;
            }
        }
        return new LodTile(k, top, mat, conf == null ? Confidence.UNKNOWN : conf);
    }
}
