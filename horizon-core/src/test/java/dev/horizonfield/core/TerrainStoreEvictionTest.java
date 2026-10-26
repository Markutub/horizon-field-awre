package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.TerrainStore;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * FullPolicy.EVICT_OLDEST: a full store follows where the players are now instead of freezing on whatever was captured first.
 * (REFUSE_NEW, the default, stays pinned by TerrainStoreCapTest.)
 */
class TerrainStoreEvictionTest {
    private static TerrainStore store(int cap) {
        TerrainStore s = new TerrainStore(4, TerrainStore.Reduce.MEAN, 64);
        s.setFullPolicy(TerrainStore.FullPolicy.EVICT_OLDEST);
        s.setMaxChunks(cap);
        return s;
    }

    private static ColumnTile at(int cx, int cz, long observedAt, int y) {
        short[] top = new short[256], mat = new short[256];
        java.util.Arrays.fill(top, (short) y); java.util.Arrays.fill(mat, (short) 1);
        return new ColumnTile(cx, cz, top, mat, Confidence.OBSERVED, observedAt);
    }

    private static boolean held(TerrainStore s, int cx, int cz) { return s.version(new LodKey(0, cx, cz)) > 0; }

    @Test void theDefaultPolicyIsStillRefuseNew() {
        assertEquals(TerrainStore.FullPolicy.REFUSE_NEW, new TerrainStore(4, TerrainStore.Reduce.MEAN, 64).fullPolicy());
    }

    @Test void aNewChunkAtTheCapReplacesTheLeastRecentlyObservedOne() {
        TerrainStore s = store(20);
        for (int i = 0; i < 20; i++) assertTrue(s.put(at(i, 0, 1000 + i, 64)) > 0);
        long v = s.put(at(500, 0, 5000, 70));
        assertTrue(v > 0, "admitted, not refused");
        assertEquals(20, s.chunkCount());
        assertTrue(held(s, 500, 0));
        assertFalse(held(s, 0, 0), "the oldest-observed chunk went");
        assertNull(s.tile(new LodKey(0, 0, 0)));
        assertTrue(held(s, 1, 0) && held(s, 19, 0));
        assertEquals(0, s.rejectedChunks());
        assertEquals(1, s.evictedChunks());
    }

    @Test void reObservingAnUnchangedChunkKeepsItYoung() {
        TerrainStore s = store(10);
        for (int i = 0; i < 10; i++) s.put(at(i, 0, 1000 + i, 64));
        long before = s.version(new LodKey(0, 0, 0));
        assertEquals(before, s.put(at(0, 0, 9000, 64)), "same content: same version, no revision churn");
        s.put(at(100, 0, 9500, 70));                                         // forces one eviction
        assertTrue(held(s, 0, 0), "chunk 0 was touched, so it survives");
        assertFalse(held(s, 1, 0), "chunk 1 is now the oldest");
    }

    @Test void afterTheCapIsReachedExploringANewAreaIsCapturedAndFarOldAreasLeave() {
        TerrainStore s = store(200);
        for (int i = 0; i < 200; i++) s.put(at(i, 0, 1000 + i, 64));          // old area, captured first
        for (int i = 0; i < 60; i++) assertTrue(s.put(at(10_000 + i, 0, 50_000 + i, 70)) > 0, "new area chunk " + i);   // the player walked away
        assertEquals(200, s.chunkCount());
        for (int i = 0; i < 60; i++) assertTrue(held(s, 10_000 + i, 0), "the new surroundings are held: " + i);
        assertEquals(0, s.rejectedChunks());
        int oldLeft = 0;
        for (int i = 0; i < 200; i++) if (held(s, i, 0)) oldLeft++;
        assertEquals(140, oldLeft, "exactly the 60 oldest of the old area were evicted (batches of cap/64 = 3 are exact only if no overshoot): " + oldLeft);
    }

    @Test void aChunkOlderThanEverythingHeldIsRefusedInsteadOfReplacingNewerData() {
        TerrainStore s = store(10);
        for (int i = 0; i < 10; i++) s.put(at(i, 0, 5000 + i, 64));
        assertEquals(0, s.put(at(99, 0, 100, 70)), "older than the oldest held chunk");
        assertEquals(10, s.chunkCount());
        assertEquals(1, s.rejectedChunks());
        assertEquals(0, s.evictedChunks());
        assertTrue(held(s, 0, 0));
    }

    @Test void theVersionIndexAboveAnEvictedChunkIsRepaired() {
        TerrainStore s = store(4);
        // chunks (0,0) (1,0) (0,1) (1,1) share one lod-1 parent (0,0); lod-2 parent (0,0)
        s.put(at(0, 0, 100, 64)); s.put(at(1, 0, 200, 64)); s.put(at(0, 1, 300, 64)); s.put(at(1, 1, 400, 64));
        LodKey p1 = new LodKey(1, 0, 0), p2 = new LodKey(2, 0, 0);
        long v1 = s.version(p1), v2 = s.version(p2);
        assertTrue(v1 > 0 && v2 > 0);
        s.put(at(50, 50, 9000, 80));                                         // evicts (0,0), the oldest
        assertFalse(held(s, 0, 0));
        assertTrue(s.version(p1) > v1, "a parent that lost a child is re-versioned so clients refresh it");
        assertNotNull(s.tile(p1), "and still built from the three remaining children");
        assertEquals(LodTile_NO_DATA(), s.tile(p1).topAt(0, 0), "the lost quarter has no data");
        // evict the rest of the quartet: nothing is left below the lod-1 parent, so it disappears
        s.put(at(51, 51, 9100, 80)); s.put(at(52, 52, 9200, 80)); s.put(at(53, 53, 9300, 80));
        assertFalse(held(s, 1, 1));
        assertEquals(0, s.version(p1), "no data below it: removed, not an empty tile");
        assertNull(s.tile(p1));
        assertEquals(0, s.version(p2));
    }

    private static int LodTile_NO_DATA() { return dev.horizonfield.core.terrain.LodTile.NO_DATA; }

    @Test void memoryAccountingStaysExactAcrossManyEvictions() {
        TerrainStore s = store(100);
        int each = at(0, 0, 1, 64).approxHeapBytes();
        long t = 0;
        for (int i = 0; i < 2000; i++) s.put(at(i, i % 7, ++t, 60 + i % 5));
        assertEquals(100, s.chunkCount());
        assertEquals((long) 100 * each, s.approxChunkBytes());
        assertEquals(2000 - 100, s.evictedChunks());
    }

    @Test void concurrentWritersAtTheCapNeverCorruptTheStore() throws Exception {
        TerrainStore s = store(500);
        Thread[] th = new Thread[4];
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
        for (int w = 0; w < th.length; w++) {
            final int seed = w;
            th[w] = new Thread(() -> {
                try {
                    Random r = new Random(seed);
                    for (int i = 0; i < 20_000; i++) s.put(at(r.nextInt(2000), r.nextInt(50), clock.incrementAndGet(), 60 + r.nextInt(10)));
                } catch (Throwable t) { err.set(t); }
            });
            th[w].start();
        }
        for (Thread t : th) t.join();
        assertNull(err.get(), String.valueOf(err.get()));
        assertTrue(s.chunkCount() <= 500 + th.length, "bounded: " + s.chunkCount());
        // every held chunk is readable and consistent with the index
        int readable = 0;
        for (int x = 0; x < 2000; x++) for (int z = 0; z < 50; z++) if (s.version(new LodKey(0, x, z)) > 0) { assertNotNull(s.tile(new LodKey(0, x, z))); readable++; }
        assertEquals(s.chunkCount(), readable, "the version index and the chunk map agree");
    }
}
