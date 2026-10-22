package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TimingWindowTest {
    @Test void emptyWindowReportsZeros() {
        TimingWindow.Summary s = new TimingWindow(8).summary();
        assertEquals(0, s.total()); assertEquals(0, s.window()); assertEquals(0, s.p99()); assertEquals(0, s.max());
    }

    @Test void nearestRankPercentilesOverOneToHundred() {
        TimingWindow w = new TimingWindow(100);
        for (int i = 100; i >= 1; i--) w.record(i);            // insertion order must not matter
        TimingWindow.Summary s = w.summary();
        assertEquals(100, s.window()); assertEquals(50, s.p50()); assertEquals(95, s.p95()); assertEquals(99, s.p99()); assertEquals(100, s.max());
    }

    @Test void memoryIsBoundedAndOldSamplesAgeOutButTheAllTimeMaxIsKept() {
        TimingWindow w = new TimingWindow(4);
        w.record(1_000);                                       // an early spike
        for (int i = 0; i < 10; i++) w.record(10);
        TimingWindow.Summary s = w.summary();
        assertEquals(11, s.total()); assertEquals(4, s.window());
        assertEquals(10, s.p99(), "the spike left the retained window");
        assertEquals(1_000, s.max(), "but the all-time max remembers it");
    }

    @Test void negativeSamplesAreClampedAndBadCapacityIsRejected() {
        TimingWindow w = new TimingWindow(2);
        w.record(-5);
        assertEquals(0, w.summary().max());
        assertThrows(IllegalArgumentException.class, () -> new TimingWindow(0));
        assertThrows(IllegalArgumentException.class, () -> new TimingWindow((1 << 20) + 1));
    }

    @Test void formatIsHumanReadableMilliseconds() {
        TimingWindow w = new TimingWindow(4);
        w.record(500_000);
        assertEquals("n=1 p50=0.50ms p95=0.50ms p99=0.50ms max=0.50ms", w.summary().format());
    }
}
