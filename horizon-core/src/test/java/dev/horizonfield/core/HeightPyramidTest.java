package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HeightPyramidTest {
    @Test
    void parentAggregatesMinMaxAndCompleteness() {
        HeightPyramid p = new HeightPyramid(2);
        p.put(TestUtil.flat(0, 0, 60, 1));
        p.put(TestUtil.flat(1, 0, 100, 2));
        PyramidNode n = p.get(1, 0, 0);
        assertNotNull(n);
        assertEquals(60, n.minY());
        assertEquals(100, n.maxY());
        assertEquals(2, n.material());
        assertFalse(n.complete(), "only 2 of 4 children known");
        p.put(TestUtil.flat(0, 1, 70, 1));
        p.put(TestUtil.flat(1, 1, 80, 1));
        assertTrue(p.get(1, 0, 0).complete());
        assertEquals(100, p.maxSeenY());
    }

    @Test
    void negativeCoordinatesUseFloorDiv() {
        HeightPyramid p = new HeightPyramid(1);
        p.put(TestUtil.flat(-1, -1, 64, 1));
        assertNotNull(p.get(1, -1, -1));
        assertNull(p.get(1, 0, 0));
    }

    @Test
    void removeCleansParents() {
        HeightPyramid p = new HeightPyramid(2);
        p.put(TestUtil.flat(3, 3, 64, 1));
        p.remove(3, 3);
        assertNull(p.get(0, 3, 3));
        assertNull(p.get(1, 1, 1));
        assertNull(p.get(2, 0, 0));
    }
}
