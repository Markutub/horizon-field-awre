package dev.awre.stream;

import dev.awre.protocol.ClientSession;
import dev.awre.protocol.TileKey;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import dev.horizonfield.core.terrain.TerrainMesher;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Client side, between the tiles the {@link ClientSession} holds and a GPU renderer. Pure Java, no Minecraft classes.
 * Each {@link #update} (call it from the client thread, ~20 times a second is plenty) decides which held tiles should be drawn:
 * <ul>
 * <li>tiles that lie completely inside the square vanilla already renders are skipped (drawing them would z-fight);</li>
 * <li>tiles further than {@code maxDistance} are skipped;</li>
 * <li>a coarse tile is hidden once its four children are all drawable (or inside vanilla range), so a LOD transition never draws
 *     two layers on top of each other, while a missing child keeps the parent visible (no hole);</li>
 * <li>meshes are built on ONE background thread, nearest first, at most {@code maxInFlight} queued at a time, so the render thread
 *     never decodes or meshes; a tile whose revision changed keeps drawing its old mesh until the new one is ready (no flicker).</li>
 * </ul>
 * Meshes are tile-local ({@link TerrainMesher#meshLocal}: positions relative to {@code mesh.originX/originZ}); the renderer adds
 * (origin - camera) in double precision per tile, so very large and negative world coordinates keep full float precision.
 * The renderer compares {@link Drawable#revision()} with what it uploaded to know when to re-upload, and frees buffers of keys that
 * left the list.
 */
public final class ClientHorizonView implements AutoCloseable {
    /** What the view needs from the client's tile store (adapter over {@link ClientSession}, or a fake in tests). */
    public interface TileSource {
        Set<TileKey> keys();
        ClientSession.Received tile(TileKey key);
        List<String> palette(TileKey key);

        static TileSource of(ClientSession s) {
            return new TileSource() {
                @Override public Set<TileKey> keys() { return s.tileKeys(); }
                @Override public ClientSession.Received tile(TileKey k) { return s.tile(k); }
                @Override public List<String> palette(TileKey k) { return s.palette(k.worldMsb(), k.worldLsb(), k.dimension()); }
            };
        }
    }

    public record Drawable(TileKey key, long revision, TerrainMesher.Mesh mesh) { }

    public record Stats(int held, int candidates, int ready, int hiddenByVanilla, int hiddenByFiner, int tooFar, int drawn,
                        long triangles, int pending, long built, long failures) { }

    private record Built(long revision, TerrainMesher.Mesh mesh) { }

    private final TileSource source;
    private final int maxMeshes, maxInFlight;
    private final Executor executor;
    private final ExecutorService ownedPool;
    private final ConcurrentHashMap<TileKey, Built> built = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TileKey, Long> failedAt = new ConcurrentHashMap<>();   // key -> revision that could not be decoded
    private final Set<TileKey> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong builtCount = new AtomicLong(), failureCount = new AtomicLong();
    private volatile Stats stats = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    // last update() parameters, for select(); client thread only
    private List<Box> lastOrder = List.of();
    private double lastCamX, lastCamZ, lastVanilla, lastMaxDistance;

    public ClientHorizonView(ClientSession session, int maxMeshes) { this(TileSource.of(session), maxMeshes, 4); }

    public ClientHorizonView(TileSource source, int maxMeshes, int maxInFlight) {
        this.source = source;
        this.maxMeshes = checkPositive(maxMeshes, "maxMeshes");
        this.maxInFlight = checkPositive(maxInFlight, "maxInFlight");
        this.ownedPool = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "awre-mesher"); t.setDaemon(true); return t; });
        this.executor = ownedPool;
    }

    /** For tests: run mesh jobs on the given executor (e.g. {@code Runnable::run}). */
    public ClientHorizonView(TileSource source, int maxMeshes, int maxInFlight, Executor executor) {
        this.source = source;
        this.maxMeshes = checkPositive(maxMeshes, "maxMeshes");
        this.maxInFlight = checkPositive(maxInFlight, "maxInFlight");
        this.ownedPool = null;
        this.executor = executor;
    }

    private static int checkPositive(int v, String name) { if (v < 1) throw new IllegalArgumentException(name); return v; }

    private static final class Box {
        final TileKey key; final double x0, x1, z0, z1; double dist;
        Box(TileKey k) {
            key = k;
            double size = 16.0 * (1L << k.lod());
            x0 = k.tileX() * size; x1 = x0 + size; z0 = k.tileZ() * size; z1 = z0 + size;
        }
        void measure(double cx, double cz) {
            double dx = Math.max(Math.max(x0 - cx, cx - x1), 0), dz = Math.max(Math.max(z0 - cz, cz - z1), 0);
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        boolean insideSquare(double cx, double cz, double r) { return x0 >= cx - r && x1 <= cx + r && z0 >= cz - r && z1 <= cz + r; }
    }

    /**
     * @param vanillaRadius half-side in blocks of the square vanilla draws around the camera (render distance * 16); 0 = none
     * @param maxDistance   nearest-point distance beyond which tiles are not drawn
     * @param worldFilter   keeps only tiles of the dimension the player is in (null = all)
     * @return tiles to draw, nearest first. The list is a snapshot; meshes are immutable.
     */
    public List<Drawable> update(double camX, double camZ, double vanillaRadius, double maxDistance, Predicate<TileKey> worldFilter) {
        Set<TileKey> held = source.keys();
        built.keySet().retainAll(held);            // DROPped tiles disappear
        failedAt.keySet().retainAll(held);
        inFlight.retainAll(held);

        Map<TileKey, Box> cand = new HashMap<>();
        int vanilla = 0, far = 0;
        for (TileKey k : held) {
            if (worldFilter != null && !worldFilter.test(k)) continue;
            Box b = new Box(k);
            if (vanillaRadius > 0 && b.insideSquare(camX, camZ, vanillaRadius)) { vanilla++; continue; }
            b.measure(camX, camZ);
            if (b.dist > maxDistance) { far++; continue; }
            cand.put(k, b);
        }

        // 1. schedule builds, nearest first
        List<Box> order = new ArrayList<>(cand.values());
        order.sort(Comparator.comparingDouble(b -> b.dist));
        lastOrder = order; lastCamX = camX; lastCamZ = camZ; lastVanilla = vanillaRadius; lastMaxDistance = maxDistance;
        for (Box b : order) {
            if (inFlight.size() >= maxInFlight) break;
            TileKey k = b.key;
            if (inFlight.contains(k)) continue;
            ClientSession.Received rec = source.tile(k);
            if (rec == null) continue;
            Built have = built.get(k);
            if (have != null && have.revision() == rec.revision()) continue;
            Long bad = failedAt.get(k);
            if (bad != null && bad == rec.revision()) continue;
            inFlight.add(k);
            executor.execute(() -> build(k, rec));
        }

        // 2. memory cap: drop the farthest meshes (from the camera) beyond maxMeshes
        if (built.size() > maxMeshes) {
            List<TileKey> keys = new ArrayList<>(built.keySet());
            keys.sort(Comparator.comparingDouble((TileKey k) -> { Box b = new Box(k); b.measure(camX, camZ); return b.dist; }).reversed());
            for (int i = 0; i < keys.size() && built.size() > maxMeshes; i++) built.remove(keys.get(i));
        }

        // 3. coverage: hide a tile when all four children are satisfied
        Map<TileKey, Boolean> memo = new HashMap<>();
        List<Drawable> out = new ArrayList<>();
        int hiddenFiner = 0, ready = 0;
        long tris = 0;
        for (Box b : order) {
            Built m = built.get(b.key);
            if (m == null) continue;
            ready++;
            if (covered(b.key, camX, camZ, vanillaRadius, maxDistance, memo, built::containsKey)) { hiddenFiner++; continue; }
            out.add(new Drawable(b.key, m.revision(), m.mesh()));
            tris += m.mesh().triangleCount();
        }
        stats = new Stats(held.size(), cand.size(), ready, vanilla, hiddenFiner, far, out.size(), tris, inFlight.size(), builtCount.get(), failureCount.get());
        return out;                                  // already nearest first (order is sorted and filtered in place)
    }

    /** Nearest-first keys of the tiles that were candidates (held, in this dimension, outside vanilla, within range) at the last {@link #update}. */
    public List<TileKey> candidateOrder() {
        List<TileKey> r = new ArrayList<>(lastOrder.size());
        for (Box b : lastOrder) r.add(b.key);
        return r;
    }

    /**
     * Which candidate tiles to DRAW right now, given which tiles a renderer can actually draw ({@code ready}, e.g. "uploaded to the GPU").
     * Same coverage rule as {@link #update} but with GPU readiness instead of CPU readiness: a coarse tile stays visible until all four
     * children are ready, and a tile that is not ready is never drawn, so an upload that lags behind the CPU mesh cannot open a hole
     * or draw two layers. Nearest first. Call on the client thread after {@link #update}.
     */
    public List<TileKey> select(Predicate<TileKey> ready) {
        Map<TileKey, Boolean> memo = new HashMap<>();
        // coarse first, so that "an ancestor is already drawn" is known when its descendants are looked at
        List<Box> coarseFirst = new ArrayList<>(lastOrder);
        coarseFirst.sort(Comparator.comparingInt((Box b) -> b.key.lod()).reversed());
        Set<TileKey> drawn = new HashSet<>();
        for (Box b : coarseFirst) {
            if (!ready.test(b.key)) continue;
            if (covered(b.key, lastCamX, lastCamZ, lastVanilla, lastMaxDistance, memo, ready)) continue;
            if (ancestorDrawn(b.key, drawn)) continue;      // a coarse tile still shows because some sibling is not ready: no second layer on top of it
            drawn.add(b.key);
        }
        List<TileKey> out = new ArrayList<>(drawn.size());
        for (Box b : lastOrder) if (drawn.contains(b.key)) out.add(b.key);   // nearest first
        return out;
    }

    private static boolean ancestorDrawn(TileKey k, Set<TileKey> drawn) {
        int tx = k.tileX(), tz = k.tileZ();
        for (int lod = k.lod() + 1; lod <= 24; lod++) {
            tx = Math.floorDiv(tx, 2); tz = Math.floorDiv(tz, 2);
            if (drawn.contains(new TileKey(k.worldMsb(), k.worldLsb(), k.dimension(), tx, tz, lod))) return true;
        }
        return false;
    }

    private boolean covered(TileKey k, double cx, double cz, double vr, double maxD, Map<TileKey, Boolean> memo, Predicate<TileKey> ready) {
        if (k.lod() == 0) return false;
        Boolean known = memo.get(k);
        if (known != null) return known;
        boolean all = true;
        for (int dx = 0; dx < 2 && all; dx++) for (int dz = 0; dz < 2 && all; dz++) {
            TileKey c = new TileKey(k.worldMsb(), k.worldLsb(), k.dimension(), k.tileX() * 2 + dx, k.tileZ() * 2 + dz, k.lod() - 1);
            Box b = new Box(c);
            if (vr > 0 && b.insideSquare(cx, cz, vr)) continue;                    // vanilla draws it
            b.measure(cx, cz);
            boolean drawn = b.dist <= maxD && ready.test(c);
            if (!drawn && !covered(c, cx, cz, vr, maxD, memo, ready)) all = false;
        }
        memo.put(k, all);
        return all;
    }

    private void build(TileKey key, ClientSession.Received rec) {
        try {
            LodTile tile = LodTileCodec.decode(new LodKey(key.lod(), key.tileX(), key.tileZ()), rec.decoded());
            List<String> pal = source.palette(key);
            TerrainMesher.Mesh m = TerrainMesher.meshLocal(tile, id -> id >= 0 && id < pal.size() ? pal.get(id) : "minecraft:air", Math.max(4, 2 << key.lod()));
            built.put(key, new Built(rec.revision(), m));
            failedAt.remove(key);
            builtCount.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            failedAt.put(key, rec.revision());
            failureCount.incrementAndGet();
        } finally {
            inFlight.remove(key);
        }
    }

    public Stats stats() { return stats; }

    /** Number of keys currently drawn is in {@link Stats#drawn()}; this is the set held in memory (for the renderer's cleanup). */
    public Set<TileKey> meshedKeys() { return new HashSet<>(built.keySet()); }

    @Override public void close() {
        if (ownedPool != null) ownedPool.shutdownNow();
        built.clear();
        failedAt.clear();
        inFlight.clear();
    }
}
