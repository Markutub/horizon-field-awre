package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.stream.StoreBudget.Resolved;
import dev.horizonfield.core.terrain.TerrainStore;
import org.junit.jupiter.api.Test;

class StoreBudgetTest {
    private static final long GIB = 1L << 30, MIB = 1L << 20;

    @Test void autoIsAnEighthOfTheHeapCappedAt512MiB() {
        Resolved small = StoreBudget.resolve(0, 2 * GIB);                      // 2 GiB heap -> 256 MiB
        assertTrue(small.auto()); assertEquals(256 * MIB, small.budgetBytes());
        assertEquals((int) (256 * MIB / TerrainStore.BYTES_PER_CHUNK_ESTIMATE), small.maxChunks());
        Resolved big = StoreBudget.resolve(0, 64 * GIB);                       // plenty of heap: capped
        assertEquals(StoreBudget.AUTO_CAP_BYTES, big.budgetBytes());
        assertTrue(big.maxChunks() > 1_000_000 && big.maxChunks() < 1_300_000, "512 MiB is roughly 1.2M chunks: " + big.maxChunks());
    }

    @Test void configuredMibWinsAndIsNotLimitedByTheHeapShare() {
        Resolved r = StoreBudget.resolve(100, 1 * GIB);
        assertFalse(r.auto()); assertEquals(100 * MIB, r.budgetBytes());
        assertEquals((int) (100 * MIB / TerrainStore.BYTES_PER_CHUNK_ESTIMATE), r.maxChunks());
    }

    @Test void tinyHeapsStillGetAUsableMinimumAndBadValuesAreRejected() {
        assertEquals(1024, StoreBudget.resolve(0, 0).maxChunks(), "never below the floor");
        assertEquals(1024, StoreBudget.resolve(0, -5).maxChunks());
        assertThrows(IllegalArgumentException.class, () -> StoreBudget.resolve(-1, GIB));
        assertThrows(IllegalArgumentException.class, () -> StoreBudget.resolve(StoreBudget.MAX_CONFIGURED_MIB + 1, GIB));
        assertTrue(StoreBudget.resolve(StoreBudget.MAX_CONFIGURED_MIB, GIB).maxChunks() > 0, "the largest allowed value does not overflow");
    }
}
