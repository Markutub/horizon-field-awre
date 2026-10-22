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

    public record Versioned(LodTile tile, long version) { }
    private record Cached(long version, LodTile tile) { }

    private final int maxLod;
    private final Reduce reduce;
    private final Map<Long, ColumnTile> chunks = new ConcurrentHashMap<>();
    private final Map<LodKey, Long> versions = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong(0);
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

    /** @return the new version, or the existing one if the content is identical */
    public long put(ColumnTile t) {
        long k = ck(t.chunkX(), t.chunkZ());
        ColumnTile old = chunks.get(k);
        if (old != null && old.sameContent(t)) return versions.get(new LodKey(0, t.chunkX(), t.chunkZ()));
        long v = clock.incrementAndGet();
        ColumnTile prev = chunks.put(k, t);
        columnBytes.addAndGet(t.approxHeapBytes() - (prev == null ? 0 : prev.approxHeapBytes()));
        LodKey n = new LodKey(0, t.chunkX(), t.chunkZ());
        for (int l = 0; l <= maxLod; l++) { versions.merge(n, v, Math::max); if (l < maxLod) n = n.parent(); }
        return v;
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
