package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ClientSession;
import dev.awre.protocol.Provenance;
import dev.awre.protocol.TileKey;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import dev.horizonfield.core.terrain.TerrainMesher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** PHASE_02: dimension filtering, revisions, reconnects, and a randomized coverage property over LOD transitions. */
class WorldCorrectnessTest {
    private static TileKey key(String dim, int lod, int tx, int tz) { return new TileKey(1, 2, dim, tx, tz, lod); }
    private static TileKey key(int lod, int tx, int tz) { return key("minecraft:overworld", lod, tx, tz); }

    private static final class Store implements ClientHorizonView.TileSource {
        final Map<TileKey, ClientSession.Received> tiles = new HashMap<>();
        @Override public Set<TileKey> keys() { return new HashSet<>(tiles.keySet()); }
        @Override public ClientSession.Received tile(TileKey k) { return tiles.get(k); }
        @Override public List<String> palette(TileKey k) { return List.of("minecraft:air", "minecraft:stone"); }
        void put(TileKey k, long rev, int h) {
            short[] top = new short[256], mat = new short[256];
            java.util.Arrays.fill(top, (short) h); java.util.Arrays.fill(mat, (short) 1);
            tiles.put(k, new ClientSession.Received(rev, Provenance.values()[0], LodTileCodec.encode(new LodTile(new LodKey(k.lod(), k.tileX(), k.tileZ()), top, mat, Confidence.OBSERVED))));
        }
    }

    private static final class FakeGpu implements MeshResidency.Backend<Integer> {
        int next = 1, uploads, frees; final Set<Integer> live = new HashSet<>();
        @Override public Integer upload(TileKey k, TerrainMesher.Mesh m) { live.add(next); uploads++; return next++; }
        @Override public void free(Integer h) { if (!live.remove(h)) throw new IllegalStateException("double free " + h); frees++; }
    }

    private static final MeshResidency.Limits BIG = new MeshResidency.Limits(100000, 1L << 30, 100000, 1L << 30);

    // ---- dimension ----

    @Test void resourceKeyTextIsParsedAndGarbageIsRejected() {
        assertEquals("minecraft:overworld", DimensionGate.fromResourceKeyString("ResourceKey[minecraft:dimension / minecraft:overworld]"));
        assertEquals("minecraft:the_nether", DimensionGate.fromResourceKeyString("ResourceKey[minecraft:dimension / minecraft:the_nether]"));
        assertEquals("mod:custom_dim", DimensionGate.fromResourceKeyString("ResourceKey[minecraft:dimension / mod:custom_dim]"));
        assertNull(DimensionGate.fromResourceKeyString(null));
        assertNull(DimensionGate.fromResourceKeyString("minecraft:overworld"));
        assertNull(DimensionGate.fromResourceKeyString("ResourceKey[minecraft:dimension / ]"));
        assertNull(DimensionGate.fromResourceKeyString("ResourceKey[a / b c]"));
    }

    @Test void theLevelsDimensionBeatsTheLastReceivedTile() {
        TileKey nether = key("minecraft:the_nether", 0, 10, 0), over = key(0, 10, 0);
        assertTrue(DimensionGate.filter("minecraft:overworld", nether).test(over), "current dimension wins over a stale last tile");
        assertFalse(DimensionGate.filter("minecraft:overworld", nether).test(nether));
        assertTrue(DimensionGate.filter(null, nether).test(nether), "fallback to the last tile only while the level is unknown");
        assertNull(DimensionGate.filter(null, null));
    }

    @Test void afterADimensionChangeOnlyTheNewDimensionIsUploaded() {
        Store s = new Store(); FakeGpu gpu = new FakeGpu();
        s.put(key("minecraft:overworld", 0, 10, 0), 1, 64);
        s.put(key("minecraft:the_nether", 0, 10, 0), 1, 40);
        try (HorizonDrawPlanner<Integer> p = new HorizonDrawPlanner<>(new ClientHorizonView(s, 100, 100, Runnable::run), new MeshResidency<>(gpu, BIG))) {
            p.tick(0, 0, 0, 5000, DimensionGate.filter("minecraft:overworld", null));
            assertEquals(Set.of(key(0, 10, 0)), keys(p.frame(0, 80, 0)));
            p.release();                                         // Minecraft.level changed
            // the OLD last-tile rule would still say overworld here; the level says the nether
            p.tick(0, 0, 0, 5000, DimensionGate.filter("minecraft:the_nether", key(0, 10, 0)));
            assertEquals(Set.of(key("minecraft:the_nether", 0, 10, 0)), keys(p.frame(0, 80, 0)));
            assertEquals(1, gpu.live.size());
        }
    }

    private static Set<TileKey> keys(List<HorizonDrawPlanner.Draw<Integer>> d) { Set<TileKey> r = new HashSet<>(); for (var x : d) r.add(x.key()); return r; }

    // ---- revisions ----

    @Test void rapidRevisionsAlwaysEndOnTheNewestAndNeverLeakHandles() {
        Store s = new Store(); FakeGpu gpu = new FakeGpu();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        try (HorizonDrawPlanner<Integer> p = new HorizonDrawPlanner<>(new ClientHorizonView(s, 100, 4, queue::add), new MeshResidency<>(gpu, BIG))) {
            s.put(key(0, 10, 0), 1, 64);
            p.tick(0, 0, 0, 5000, null); queue.poll().run(); p.tick(0, 0, 0, 5000, null);
            assertEquals(1, p.frame(0, 80, 0).get(0).revision());
            s.put(key(0, 10, 0), 2, 70);
            p.tick(0, 0, 0, 5000, null);                          // rev 2 build queued
            s.put(key(0, 10, 0), 3, 75);                          // newer revision arrives while rev 2 is still queued
            queue.poll().run();                                   // rev 2 finishes (captured at schedule time)
            p.tick(0, 0, 0, 5000, null);
            assertTrue(p.frame(0, 80, 0).get(0).revision() <= 2, "newest not ready yet: an older valid mesh is still drawn");
            while (!queue.isEmpty()) { queue.poll().run(); p.tick(0, 0, 0, 5000, null); p.frame(0, 80, 0); }
            assertEquals(3, p.frame(0, 80, 0).get(0).revision());
            assertEquals(1, gpu.live.size(), "exactly one handle alive for one tile");
        }
        assertEquals(0, gpu.live.size());
    }

    // ---- reconnect ----

    @Test void reconnectingManyTimesLeaksNothing() {
        FakeGpu gpu = new FakeGpu();
        for (int session = 0; session < 5; session++) {
            Store s = new Store();
            for (int i = 10; i < 16; i++) s.put(key(0, i, session), 1, 64);
            HorizonDrawPlanner<Integer> p = new HorizonDrawPlanner<>(new ClientHorizonView(s, 100, 100, Runnable::run), new MeshResidency<>(gpu, BIG));
            p.tick(0, 0, 0, 5000, null);
            assertTrue(p.frame(0, 80, 0).size() > 0);
            if (session % 2 == 0) p.release();                    // some disconnects release first, as AwreClientMod does ...
            p.close();                                            // ... and always close afterwards
            assertEquals(0, gpu.live.size(), "no handles alive after disconnect " + session);
        }
        assertEquals(gpu.uploads, gpu.frees);
    }

    // ---- LOD coverage property ----

    /** Randomized: random pyramid of held tiles, random camera, random "uploaded" subset. */
    @Test void randomizedCoverageNeverDoubleLayersAndNeverHolesWhereSomethingIsReady() {
        Random rnd = new Random(20261022L);
        final int SIDE = 16;                                      // lod0 tiles per axis in the test region (power of two)
        for (int iter = 0; iter < 400; iter++) {
            Store s = new Store();
            for (int lod = 0; lod <= 4; lod++) {
                int n = SIDE >> lod;
                for (int tx = 0; tx < n; tx++) for (int tz = 0; tz < n; tz++) if (rnd.nextInt(100) < 55) s.put(key(lod, tx, tz), 1, 60);
            }
            double camX = rnd.nextDouble() * SIDE * 16, camZ = rnd.nextDouble() * SIDE * 16;
            double vanilla = rnd.nextBoolean() ? 0 : 16 * (1 + rnd.nextInt(6));
            double maxD = rnd.nextBoolean() ? 100000 : 40 + rnd.nextInt(200);
            try (ClientHorizonView v = new ClientHorizonView(s, 100000, 100000, Runnable::run)) {
                v.update(camX, camZ, vanilla, maxD, null);
                List<TileKey> cand = v.candidateOrder();
                Set<TileKey> candSet = new HashSet<>(cand);
                Set<TileKey> ready = new HashSet<>();
                for (TileKey k : cand) if (rnd.nextInt(100) < 70) ready.add(k);   // GPU upload lag: only some are resident
                List<TileKey> drawn = v.select(ready::contains);
                String ctx = "iter " + iter + " cam=(" + (int) camX + "," + (int) camZ + ") vanilla=" + vanilla + " maxD=" + maxD;

                for (TileKey k : drawn) assertTrue(ready.contains(k) && candSet.contains(k), ctx + ": drew a tile that is not resident/candidate " + k);
                for (int i = 0; i < drawn.size(); i++) for (int j = i + 1; j < drawn.size(); j++)
                    assertFalse(overlap(drawn.get(i), drawn.get(j)), ctx + ": double layer " + drawn.get(i) + " vs " + drawn.get(j));

                for (int tx = 0; tx < SIDE; tx++) for (int tz = 0; tz < SIDE; tz++) {
                    double x0 = tx * 16, z0 = tz * 16;
                    boolean insideVanilla = vanilla > 0 && x0 >= camX - vanilla && x0 + 16 <= camX + vanilla && z0 >= camZ - vanilla && z0 + 16 <= camZ + vanilla;
                    if (insideVanilla) continue;
                    boolean readyHere = false;
                    for (TileKey k : ready) if (contains(k, tx, tz)) readyHere = true;
                    if (!readyHere) continue;
                    boolean drawnHere = false;
                    for (TileKey k : drawn) if (contains(k, tx, tz)) drawnHere = true;
                    assertTrue(drawnHere, ctx + ": hole at lod0 cell (" + tx + "," + tz + ") although a resident tile covers it");
                }
            }
        }
    }

    private static boolean contains(TileKey k, int lod0x, int lod0z) {
        return Math.floorDiv(lod0x, 1 << k.lod()) == k.tileX() && Math.floorDiv(lod0z, 1 << k.lod()) == k.tileZ();
    }
    private static boolean overlap(TileKey a, TileKey b) {
        TileKey coarse = a.lod() >= b.lod() ? a : b, fine = coarse == a ? b : a;
        return Math.floorDiv(fine.tileX(), 1 << (coarse.lod() - fine.lod())) == coarse.tileX() && Math.floorDiv(fine.tileZ(), 1 << (coarse.lod() - fine.lod())) == coarse.tileZ();
    }
}
