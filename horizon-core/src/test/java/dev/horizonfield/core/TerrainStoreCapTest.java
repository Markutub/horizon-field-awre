package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.TerrainStore;
import org.junit.jupiter.api.Test;

class TerrainStoreCapTest {
    private static TerrainStore store() { return new TerrainStore(4, TerrainStore.Reduce.MEAN, 64); }

    @Test void unlimitedByDefaultAndTheCapRejectsOnlyNewCoordinates() {
        TerrainStore s = store();
        assertEquals(Integer.MAX_VALUE, s.maxChunks());
        for (int i = 0; i < 50; i++) assertTrue(s.put(TestUtil.flat(i, 0, 64, 1)) > 0);
        assertEquals(50, s.chunkCount()); assertEquals(0, s.rejectedChunks());

        s.setMaxChunks(50);                                                    // exactly full
        assertEquals(0, s.put(TestUtil.flat(100, 0, 64, 1)), "a new coordinate is refused");
        assertEquals(0, s.put(TestUtil.flat(101, 0, 64, 1)));
        assertEquals(50, s.chunkCount()); assertEquals(2, s.rejectedChunks());
        assertEquals(0, s.version(new LodKey(0, 100, 0)), "nothing was recorded for a refused chunk");
        assertNull(s.tile(new LodKey(0, 100, 0)));
    }

    @Test void chunksAlreadyHeldKeepUpdatingAtTheCapAndNothingHeldIsEverDropped() {
        TerrainStore s = store();
        for (int i = 0; i < 10; i++) s.put(TestUtil.flat(i, 0, 64, 1));
        s.setMaxChunks(10);
        long before = s.version(new LodKey(0, 3, 0));
        long v = s.put(TestUtil.flat(3, 0, 90, 1));                            // a real edit of a stored chunk
        assertTrue(v > before, "updates of held chunks are still accepted when full");
        assertEquals(90, s.tile(new LodKey(0, 3, 0)).topAt(0, 0));
        assertEquals(v, s.put(TestUtil.flat(3, 0, 90, 1)), "identical content is still a no-op returning the existing version");
        assertEquals(10, s.chunkCount()); assertEquals(0, s.rejectedChunks(), "an update is not a rejection");
        for (int i = 0; i < 10; i++) assertNotNull(s.tile(new LodKey(0, i, 0)), "chunk " + i + " is still held");
    }

    @Test void raisingTheCapLetsNewChunksInAndBadCapsAreRejected() {
        TerrainStore s = store();
        s.setMaxChunks(1);
        assertTrue(s.put(TestUtil.flat(0, 0, 64, 1)) > 0);
        assertEquals(0, s.put(TestUtil.flat(1, 0, 64, 1)));
        s.setMaxChunks(2);
        assertTrue(s.put(TestUtil.flat(1, 0, 64, 1)) > 0);
        assertEquals(2, s.chunkCount());
        assertThrows(IllegalArgumentException.class, () -> s.setMaxChunks(0));
        assertThrows(IllegalArgumentException.class, () -> s.setMaxChunks(-5));
    }

    @Test void theCoarseTilesOfHeldChunksStayCorrectWhenNewOnesAreRefused() {
        TerrainStore s = store();
        s.setMaxChunks(4);
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) s.put(TestUtil.flat(x, z, 60 + x * 10, 1));
        long ver = s.version(new LodKey(1, 0, 0));
        assertEquals(0, s.put(TestUtil.flat(2, 0, 200, 1)));                   // refused: must not disturb any ancestor
        assertEquals(ver, s.version(new LodKey(1, 0, 0)));
        assertEquals(0, s.version(new LodKey(1, 1, 0)), "the ancestor of a refused chunk got no version");
        assertNull(s.tile(new LodKey(0, 2, 0)));
    }
}
