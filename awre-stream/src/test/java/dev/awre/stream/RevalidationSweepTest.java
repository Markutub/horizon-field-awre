package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.CaptureBacklog;
import dev.horizonfield.core.ChunkView;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.TileLog;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.TerrainStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The periodic re-observation of loaded chunks (stream.revalidate-seconds) is only acceptable if it is free for chunks that did not
 * change and finds those that did. Pipeline under test: CaptureBacklog (deliberately smaller than the world, so it overflows) ->
 * ChunkIngest -> TerrainStore -> TerrainPersistence.
 */
class RevalidationSweepTest {
    private static ChunkView view(int cx, int cz, boolean edited) {
        return new ChunkView() {
            @Override public int chunkX() { return cx; }
            @Override public int chunkZ() { return cz; }
            @Override public int minY() { return -64; }
            @Override public int highestBlockY(int lx, int lz) { return SyntheticTerrain.heightAt(cx * 16 + lx, cz * 16 + lz, 5) + (edited && lx == 3 && lz == 4 ? 6 : 0); }
            @Override public String blockKey(int lx, int y, int lz) { return edited && lx == 3 && lz == 4 ? "minecraft:tnt" : "minecraft:stone"; }
        };
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return true; Thread.sleep(10); }
        return c.getAsBoolean();
    }

    @Test void aSweepIsFreeForUnchangedChunksAndFindsTheChangedOnes() throws Exception {
        final int side = 20, total = side * side;                  // 400 loaded chunks, backlog capacity 64: the sweep must overflow it
        Path file = Files.createTempDirectory("awre-sweep").resolve("w.hfl");
        TerrainStore store = new TerrainStore(3, TerrainStore.Reduce.MEAN, 1000);
        MaterialPalette pal = new MaterialPalette();
        Metrics m = new Metrics();
        Set<Integer> edited = new HashSet<>();
        try (TerrainPersistence per = TerrainPersistence.open(file, "fp-sweep", store, pal, m);
             ChunkIngest in = new ChunkIngest(store, pal, 4096, 1, m, t -> { }, per)) {
            CaptureBacklog<Integer> backlog = new CaptureBacklog<>(64);
            Runnable sweep = () -> {
                for (int k = 0; k < total; k++) backlog.request(k);
                int guard = 0;
                while (!backlog.idle() && guard++ < 10_000) {
                    backlog.pump(k -> k);
                    backlog.drain(k -> in.submit(view(k / side, k % side, edited.contains(k)), k), 1_000_000_000L, 16);
                }
                assertTrue(backlog.idle());
            };

            sweep.run();                                           // first observation of every chunk
            assertTrue(waitUntil(() -> m.get("ingest.stored") == total, 10_000), "stored " + m.get("ingest.stored"));
            assertEquals(total, store.chunkCount());
            assertEquals(total, m.get("persist.appended"));
            Map<Integer, Long> v1 = new HashMap<>();
            for (int k = 0; k < total; k++) v1.put(k, store.version(new LodKey(0, k / side, k % side)));

            sweep.run();                                           // nothing changed in the world
            assertTrue(waitUntil(() -> m.get("ingest.stored") == 2L * total, 10_000));
            assertEquals(total, m.get("persist.appended"), "an unchanged re-observation must not write anything");
            for (int k = 0; k < total; k++) assertEquals(v1.get(k).longValue(), store.version(new LodKey(0, k / side, k % side)), "no new revision for chunk " + k);

            for (int k = 100; k < 110; k++) edited.add(k);         // e.g. an explosion or a WorldEdit paste that fired no place/break event
            sweep.run();
            assertTrue(waitUntil(() -> m.get("ingest.stored") == 3L * total, 10_000));
            assertEquals(total + 10, m.get("persist.appended"), "exactly the 10 changed chunks are written");
            for (int k = 0; k < total; k++) {
                long now = store.version(new LodKey(0, k / side, k % side));
                if (edited.contains(k)) assertTrue(now > v1.get(k), "changed chunk " + k + " must get a new revision");
                else assertEquals(v1.get(k).longValue(), now, "unchanged chunk " + k);
            }
            assertEquals(0, m.get("persist.errors"));
            assertEquals(0, m.get("ingest.rejected"));
        }
        TerrainStore store2 = new TerrainStore(3, TerrainStore.Reduce.MEAN, 1000);
        try (TerrainPersistence p2 = TerrainPersistence.open(file, "fp-sweep", store2, new MaterialPalette(), new Metrics())) {
            TileLog.LoadReport report = p2.report();                   // also keeps the try-resource referenced (javac -Xlint:try)
            assertFalse(report.freshStart(), "the restart must replay the existing log, not start fresh: " + report.reason());
            assertEquals(0, report.discardedBytes(), "a cleanly closed log has nothing to discard");
            assertTrue(report.tiles() > 0);
            assertEquals(total, store2.chunkCount());
            assertEquals(store.tile(new LodKey(0, 5, 0)).topAt(3, 4), store2.tile(new LodKey(0, 5, 0)).topAt(3, 4), "the edit survives a restart");
        }
    }
}
