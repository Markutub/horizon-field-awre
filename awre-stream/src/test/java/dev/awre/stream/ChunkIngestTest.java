package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.ChunkView;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.TerrainStore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkIngestTest {
    /** A chunk whose surface comes from SyntheticTerrain heights, with an optional extra block on top of column (0,0). */
    private static ChunkView view(int cx, int cz, long seed, boolean bump) {
        return new ChunkView() {
            @Override public int chunkX() { return cx; }
            @Override public int chunkZ() { return cz; }
            @Override public int minY() { return -64; }
            @Override public int highestBlockY(int lx, int lz) { return SyntheticTerrain.heightAt(cx * 16 + lx, cz * 16 + lz, seed) + (bump && lx == 0 && lz == 0 ? 3 : 0); }
            @Override public String blockKey(int lx, int y, int lz) { return bump && lx == 0 && lz == 0 ? "minecraft:gold_block" : "minecraft:stone"; }
        };
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return true; Thread.sleep(10); }
        return c.getAsBoolean();
    }

    @Test void observedChunksBecomeTilesAndPaletteEntries() throws Exception {
        TerrainStore store = new TerrainStore(6, TerrainStore.Reduce.MEAN, 1000);
        MaterialPalette pal = new MaterialPalette();
        Metrics m = new Metrics();
        AtomicInteger errors = new AtomicInteger();
        try (ChunkIngest in = new ChunkIngest(store, pal, 4096, 2, m, t -> errors.incrementAndGet())) {
            for (int x = 0; x < 20; x++) for (int z = 0; z < 20; z++) assertTrue(in.submit(view(x, z, 5, false), x * x + z * z));
            assertTrue(waitUntil(() -> store.chunkCount() == 400, 5000), "stored " + store.chunkCount());
        }
        assertEquals(0, errors.get());
        LodTile t = store.tile(new LodKey(0, 3, 4));
        assertEquals(Confidence.OBSERVED, t.confidence());
        assertEquals(SyntheticTerrain.heightAt(3 * 16 + 7, 4 * 16 + 9, 5), t.topAt(7, 9));
        assertEquals(pal.idOf("minecraft:stone"), t.materialAt(7, 9));
        assertEquals(Confidence.OBSERVED, store.tile(new LodKey(3, 0, 0)).confidence());
    }

    @Test void identicalReobservationKeepsTheVersionAndAChangeBumpsIt() throws Exception {
        TerrainStore store = new TerrainStore(3, TerrainStore.Reduce.MEAN, 100);
        MaterialPalette pal = new MaterialPalette();
        try (ChunkIngest in = new ChunkIngest(store, pal, 64, 1, new Metrics(), t -> { })) {
            in.submit(view(1, 1, 9, false), 0);
            assertTrue(waitUntil(() -> store.version(new LodKey(0, 1, 1)) > 0, 3000));
            long v1 = store.version(new LodKey(0, 1, 1));
            in.submit(view(1, 1, 9, false), 0);
            Thread.sleep(200);
            assertEquals(v1, store.version(new LodKey(0, 1, 1)));
            in.submit(view(1, 1, 9, true), 0);
            assertTrue(waitUntil(() -> store.version(new LodKey(0, 1, 1)) > v1, 3000));
            assertTrue(pal.snapshot().contains("minecraft:gold_block"));
        }
    }

    @Test void overloadDropsWorkInsteadOfGrowingWithoutBound() throws Exception {
        TerrainStore store = new TerrainStore(3, TerrainStore.Reduce.MEAN, 100);
        Metrics m = new Metrics();
        try (ChunkIngest in = new ChunkIngest(store, new MaterialPalette(), 64, 1, m, t -> { })) {
            for (int i = 0; i < 20_000; i++) in.submit(view(i % 500, i / 500, 1, false), i);
            assertTrue(in.queued() <= 64, "queue " + in.queued());
        }
    }

    @Test void capturedTerrainSurvivesARestartAndATornTail() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("awre-persist");
        java.nio.file.Path file = dir.resolve("w.hfl");
        TerrainStore store = new TerrainStore(4, TerrainStore.Reduce.MEAN, 1000);
        MaterialPalette pal = new MaterialPalette();
        Metrics m = new Metrics();
        TerrainPersistence p = TerrainPersistence.open(file, "fp-1", store, pal, m);
        assertTrue(p.report().freshStart());
        try (ChunkIngest in = new ChunkIngest(store, pal, 4096, 2, m, t -> { }, p)) {
            for (int x = 0; x < 12; x++) for (int z = 0; z < 12; z++) in.submit(view(x, z, 3, x == 5 && z == 5), x + z);
            assertTrue(waitUntil(() -> store.chunkCount() == 144, 5000));
            in.submit(view(1, 1, 3, false), 0);                    // identical re-observation: must not be appended again
            Thread.sleep(200);
        }
        assertEquals(144, m.get("persist.appended"));
        assertTrue(m.get("persist.skipped-unchanged") >= 1);

        TerrainStore store2 = new TerrainStore(4, TerrainStore.Reduce.MEAN, 1000);
        MaterialPalette pal2 = new MaterialPalette();
        try (TerrainPersistence p2 = TerrainPersistence.open(file, "fp-1", store2, pal2, new Metrics())) {
            assertTrue(!p2.report().freshStart() && p2.report().tiles() == 144, "replayed " + p2.report());
            assertEquals(pal.snapshot(), pal2.snapshot());
            assertEquals(144, store2.chunkCount());
            for (LodKey k : new LodKey[]{new LodKey(0, 5, 5), new LodKey(0, 11, 0), new LodKey(2, 0, 0), new LodKey(4, 0, 0)})
                assertTrue(store.tile(k).sameContent(store2.tile(k)), "content " + k);
        }

        // a torn write at the tail: earlier chunks survive, the damaged one is dropped, nothing throws
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        java.nio.file.Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 100));
        TerrainStore store3 = new TerrainStore(4, TerrainStore.Reduce.MEAN, 1000);
        try (TerrainPersistence p3 = TerrainPersistence.open(file, "fp-1", store3, new MaterialPalette(), new Metrics())) {
            assertTrue(p3.report().discardedBytes() > 0);
            assertTrue(store3.chunkCount() < 144 && store3.chunkCount() >= 130, "kept " + store3.chunkCount());   // 100 bytes can span two small records
        }

        // a different world fingerprint starts fresh instead of mixing data
        TerrainStore store4 = new TerrainStore(4, TerrainStore.Reduce.MEAN, 1000);
        try (TerrainPersistence p4 = TerrainPersistence.open(file, "fp-OTHER", store4, new MaterialPalette(), new Metrics())) {
            assertTrue(p4.report().freshStart());
            assertEquals(0, store4.chunkCount());
        }
    }

    @Test void cacheFileIsOnlyCreatedByTheFirstPersistedTile() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("awre-lazy");
        java.nio.file.Path file = dir.resolve("terrain").resolve("w.hfl");
        TerrainStore store = new TerrainStore(4, TerrainStore.Reduce.MEAN, 1000);
        MaterialPalette pal = new MaterialPalette();
        Metrics m = new Metrics();
        try (TerrainPersistence p = TerrainPersistence.open(file, "fp-lazy", store, pal, m)) {
            assertTrue(p.report().freshStart());
            assertFalse(p.fileCreated());
            p.flush();                                              // flushing an unused cache must not create the file either
            assertFalse(java.nio.file.Files.exists(file), "an unobserved world must leave nothing on disk");
            try (ChunkIngest in = new ChunkIngest(store, pal, 64, 1, m, t -> { }, null)) { in.submit(view(0, 0, 3, false), 0); assertTrue(waitUntil(() -> store.chunkCount() == 1, 5000)); }
            p.persist(new dev.horizonfield.core.TerrainAnalyzer(pal).analyze(view(2, 2, 3, false), dev.horizonfield.core.Confidence.OBSERVED, 1L));
            assertTrue(p.fileCreated());
            assertTrue(java.nio.file.Files.size(file) > 0);
        }
        TerrainStore store2 = new TerrainStore(4, TerrainStore.Reduce.MEAN, 1000);
        try (TerrainPersistence p2 = TerrainPersistence.open(file, "fp-lazy", store2, new MaterialPalette(), new Metrics())) {
            assertTrue(!p2.report().freshStart() && p2.report().tiles() == 1, "replayed " + p2.report());
            assertTrue(p2.fileCreated());
        }
    }
}
