package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TileDeduperTest {
    @Test
    void identicalRecaptureIsNotPersistedAgainButChangeIs() {
        TileDeduper d = new TileDeduper();
        assertTrue(d.shouldPersist(TestUtil.flat(0, 0, 64, 1)));
        assertFalse(d.shouldPersist(TestUtil.flat(0, 0, 64, 1)), "same content must be skipped");
        assertTrue(d.shouldPersist(TestUtil.flat(0, 0, 65, 1)), "changed content must persist");
        assertTrue(d.shouldPersist(TestUtil.flat(1, 0, 64, 1)), "other chunk is independent");
        assertEquals(2, d.size());
    }

    @Test
    void seedingFromReplayPreventsReappend() {
        TileDeduper d = new TileDeduper();
        d.seed(TestUtil.flat(5, 5, 70, 2));
        assertFalse(d.shouldPersist(TestUtil.flat(5, 5, 70, 2)));
    }

    @Test
    void hashIgnoresObservationTimeButSeesMaterialAndConfidence() {
        ColumnTile a = TestUtil.flat(0, 0, 64, 1);
        ColumnTile sameLater = new ColumnTile(0, 0, a.topArray(), a.materialArray(), Confidence.OBSERVED, 999L);
        assertEquals(a.contentHash64(), sameLater.contentHash64());
        assertTrue(a.contentHash64() != TestUtil.flat(0, 0, 64, 2).contentHash64());
        ColumnTile predicted = new ColumnTile(0, 0, a.topArray(), a.materialArray(), Confidence.PREDICTED, 1L);
        assertTrue(a.contentHash64() != predicted.contentHash64());
    }
}
