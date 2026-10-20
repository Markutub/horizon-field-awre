package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VisibilitySweepTest {
    /** Row of chunks cz=0, cx=1..12 on a low plain (y=60) with overrides. Viewer at the middle of chunk (0,0), eye y=70. */
    private static HeightPyramid row(int ridgeCx, int ridgeY, int behindCx, int behindY) {
        HeightPyramid p = new HeightPyramid(3);
        for (int cx = 0; cx <= 12; cx++) {
            int y = 60;
            if (cx == ridgeCx) y = ridgeY;
            if (cx == behindCx) y = behindY;
            p.put(TestUtil.flat(cx, 0, y, 1));
        }
        return p;
    }

    private static boolean sees(VisibilityResult r, int cx, int cz) {
        return r.cells().stream().anyMatch(c -> c.level() == 0 && c.cx() == cx && c.cz() == cz);
    }

    private static VisibilityParams params() {
        return new VisibilityParams(8, 70, 8, 16, 220, 360, 0);
    }

    @Test
    void lowTerrainBehindTallRidgeIsHidden() {
        VisibilityResult r = VisibilitySweep.run(row(4, 200, 10, 80), params());
        assertTrue(sees(r, 4, 0), "ridge itself is visible");
        assertFalse(sees(r, 10, 0), "terrain behind the ridge must be culled");
    }

    @Test
    void tallPeakBehindRidgeStaysVisible() {
        VisibilityResult r = VisibilitySweep.run(row(4, 200, 10, 400), params());
        assertTrue(sees(r, 10, 0), "a peak taller than the horizon angle must remain visible");
    }

    @Test
    void flatGroundIsVisibleToTheHorizon() {
        VisibilityResult r = VisibilitySweep.run(row(-1, 0, -1, 0), params());
        assertTrue(sees(r, 3, 0));
        assertTrue(sees(r, 11, 0));
    }

    @Test
    void unknownTerrainIsReportedNotAssumed() {
        VisibilityResult r = VisibilitySweep.run(new HeightPyramid(3), params());
        assertTrue(r.cells().isEmpty());
        assertTrue(r.unknownProbes() > 0);
    }

    @Test
    void slackReducesOcclusion() {
        VisibilityParams tight = new VisibilityParams(8, 70, 8, 16, 220, 360, 0);
        VisibilityParams slack = new VisibilityParams(8, 70, 8, 16, 220, 360, 100);
        HeightPyramid p = row(4, 90, 10, 95);
        boolean tightSees = sees(VisibilitySweep.run(p, tight), 10, 0);
        boolean slackSees = sees(VisibilitySweep.run(p, slack), 10, 0);
        assertFalse(tightSees);
        assertTrue(slackSees);
    }

    @Test
    void chunkSetExpandsCoarseCells() {
        VisibilityResult r = new VisibilityResult(
                java.util.List.of(new VisibleCell(1, 0, 0, 60, 70, 1, Confidence.OBSERVED)), 1, 0, 0);
        assertEquals(4, r.toChunkSet().size());
    }
}
