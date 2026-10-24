package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.TileKey;
import dev.awre.stream.RevealAnimator.Params;
import dev.awre.stream.RevealAnimator.Uniform;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RevealAnimatorTest {
    private static final Params P = Params.DEFAULT;
    private static final long SEC = 1_000_000_000L;

    private static TileKey key(int lod, int tx, int tz) { return new TileKey(1, 2, "minecraft:overworld", tx, tz, lod); }

    // ---- formulas ----

    @Test void liftAndGreyAreZeroAtTheEndsAndGreyPeaksExactlyWhenTheTerrainIsUp() {
        assertEquals(-P.depth(), RevealAnimator.lift(P, 0), 1e-9, "starts emerged from depth below the true height");
        assertEquals(0, RevealAnimator.lift(P, 1), 1e-12);
        assertEquals(0, RevealAnimator.lift(P, 1.5), 1e-12, "settled stays settled");
        assertEquals(0, RevealAnimator.grey(P, 0), 1e-12);
        assertEquals(0, RevealAnimator.grey(P, 1), 1e-12);
        assertEquals(P.greyPeak(), RevealAnimator.grey(P, 0.5), 1e-12);
        double maxLift = -1e9, sAtMax = 0, maxGrey = -1, sAtGrey = 0;
        for (int i = 0; i <= 1000; i++) {
            double s = i / 1000.0;
            if (RevealAnimator.lift(P, s) > maxLift) { maxLift = RevealAnimator.lift(P, s); sAtMax = s; }
            if (RevealAnimator.grey(P, s) > maxGrey) { maxGrey = RevealAnimator.grey(P, s); sAtGrey = s; }
            assertTrue(RevealAnimator.grey(P, s) >= 0 && RevealAnimator.grey(P, s) <= P.greyPeak() + 1e-12, "grey stays within [0, greyPeak]");
        }
        assertTrue(maxLift > 0.8 * P.peak(), "it really goes up: " + maxLift);
        assertTrue(Math.abs(sAtMax - sAtGrey) < 0.2, "the highest point and the greyest point coincide (s=" + sAtMax + " vs " + sAtGrey + ")");
    }

    @Test void greyMixKeepsTheColourWhenNotGreyAndLandsOnTheGreyTargetWhenFullyGrey() {
        assertArrayEquals(new double[]{0.2, 0.5, 0.1}, RevealAnimator.greyMix(0.2, 0.5, 0.1, 0), 1e-12);
        double[] c = RevealAnimator.greyMix(0.2, 0.5, 0.1, 1);
        assertEquals(c[0], c[1], 1e-12); assertEquals(c[1], c[2], 1e-12);
        double luma = 0.2126 * 0.2 + 0.7152 * 0.5 + 0.0722 * 0.1;
        assertEquals(luma * RevealAnimator.GREY_LUMA_WEIGHT + RevealAnimator.GREY_LIFT, c[0], 1e-12);
    }

    @Test void theRingSpreadsOutNearerVerticesStartEarlierAndFarOnesAreCappedAtMaxDelay() {
        double age = 1.0;                                                      // 1 s after the session started, tiles long resident
        double near = RevealAnimator.tau(P, age, 99, 30), far = RevealAnimator.tau(P, age, 99, 100), beyond = RevealAnimator.tau(P, age, 99, 5000);
        assertTrue(near > far && far < 0 + 1e9, "nearer vertices have run longer");
        assertEquals(age - 30 / P.speed(), near, 1e-12);
        assertTrue(far < 0.2 && far > -0.0 - 1e-9 || far <= 0, "100 blocks at 120 blocks/s has not started at 0.8 s? " + far);
        assertEquals(-(P.maxDelay() - age), beyond, 1e-12, "very far vertices wait only maxDelay, not distance/speed");
        double frontAt1s = P.speed() * age;
        assertTrue(RevealAnimator.tau(P, age, 99, frontAt1s - 1) > 0 && RevealAnimator.tau(P, age, 99, frontAt1s + 1) < 0, "the wave front is speed * revealAge");
    }

    @Test void aTileThatArrivesAfterTheWavePassedStartsAtItsOwnArrival() {
        double tau = RevealAnimator.tau(P, 20, 0.25, 10);                      // session is 20 s old, this tile became drawable 0.25 s ago
        assertEquals(0.25, tau, 1e-12);
    }

    // ---- packing ----

    @Test void uniformPackingOrderIsPinned() {
        float[] u = new Params(111, 7, 2.5, 8, 9, 0.4, 0.6, 1.5, 3).toUniform();
        assertEquals(16, u.length);
        assertArrayEquals(new float[]{111, 7, 2.5f, 8, 9, 0.4f, 0.6f, 1.5f, 3, 0, 0, 0, 0, 0, 0, 0}, u, 0f);
    }

    @Test void parametersAreValidatedAndPropertyOverridesFallBackSafely() {
        assertThrows(IllegalArgumentException.class, () -> new Params(0, 6, 1.6, 6, 10, 0.3, 0.85, 1.2, 2));
        assertThrows(IllegalArgumentException.class, () -> new Params(120, 6, 0.01, 6, 10, 0.3, 0.85, 1.2, 2));
        assertThrows(IllegalArgumentException.class, () -> new Params(120, 6, 1.6, 6, 10, 0.95, 0.85, 1.2, 2));
        assertThrows(IllegalArgumentException.class, () -> new Params(120, 6, 1.6, 6, 10, 0.3, 1.5, 1.2, 2));
        Map<String, String> props = new HashMap<>();
        java.util.List<String> warns = new java.util.ArrayList<>();
        props.put("awre.reveal.speed", "200"); props.put("awre.reveal.peak", "abc");
        Params p = Params.fromProperties(props::get, warns::add);
        assertEquals(200, p.speed(), 0); assertEquals(Params.DEFAULT.peak(), p.peak(), 0);
        assertEquals(1, warns.size());
        props.put("awre.reveal.duration", "999");
        assertEquals(Params.DEFAULT, Params.fromProperties(props::get, warns::add), "an out-of-range set is rejected as a whole");
    }

    // ---- state ----

    @Test void firstDrawArmsTheWaveAtThePlayerAndTheOriginStaysFixedInTheWorld() {
        RevealAnimator a = new RevealAnimator(P, true);
        Uniform u0 = a.forDraw(key(0, 0, 0), 0, 0, 16, 100, 200, 5 * SEC);
        assertTrue(a.armed());
        assertEquals(0, u0.originRelX(), 0); assertEquals(0, u0.originRelZ(), 0); assertEquals(0, u0.revealAge(), 1e-6);
        Uniform u1 = a.forDraw(key(0, 0, 0), 0, 0, 16, 130, 190, 5 * SEC + SEC / 2);   // the player walked +30 x, -10 z
        assertEquals(-30, u1.originRelX(), 1e-4); assertEquals(10, u1.originRelZ(), 1e-4);
        assertEquals(0.5, u1.revealAge(), 1e-5); assertEquals(0.5, u1.tileAge(), 1e-5);
    }

    @Test void aNewRevisionOfTheSameTileKeepsItsClockAndNeverReplays() {
        RevealAnimator a = new RevealAnimator(P, true);
        a.forDraw(key(0, 3, 3), 48, 48, 16, 0, 0, 0);
        float t1 = a.forDraw(key(0, 3, 3), 48, 48, 16, 0, 0, SEC).tileAge();
        float t2 = a.forDraw(key(0, 3, 3), 48, 48, 16, 0, 0, SEC + SEC / 2).tileAge();   // e.g. after a re-upload of a new revision
        assertEquals(1.0, t1, 1e-5); assertEquals(1.5, t2, 1e-5);
    }

    @Test void lodSwapsOverAlreadyRevealedGroundStartFinishedButNewGroundAnimates() {
        RevealAnimator a = new RevealAnimator(P, true);
        Uniform parent = a.forDraw(key(2, 0, 0), 0, 0, 64, 0, 0, 0);               // a 64x64 tile covers 4x4 = 16 cells
        assertFalse(parent.isNone());
        Uniform child = a.forDraw(key(1, 0, 0), 0, 0, 32, 0, 0, SEC);              // refinement of the same ground
        assertTrue(child.isNone(), "an LOD swap must not replay the effect");
        Uniform elsewhere = a.forDraw(key(1, 40, 40), 1280, 1280, 32, 0, 0, SEC);
        assertFalse(elsewhere.isNone(), "ground nobody has seen yet animates");
        assertEquals(0, elsewhere.tileAge(), 1e-6);
        Uniform straddling = a.forDraw(key(1, 1, 0), 32, 0, 32, 0, 0, SEC);       // inside cell (0,0): already revealed
        assertTrue(straddling.isNone());
    }

    @Test void whenEverythingIsPastTheEndTheUniformIsNoneAndResetStartsAFreshSession() {
        RevealAnimator a = new RevealAnimator(P, true);
        a.forDraw(key(0, 0, 0), 0, 0, 16, 0, 0, 0);
        long late = (long) ((P.maxDelay() + P.duration() + 1) * SEC);
        assertTrue(a.forDraw(key(0, 0, 0), 0, 0, 16, 0, 0, late).isNone(), "finished tiles cost the shader nothing");
        a.reset();
        assertFalse(a.armed()); assertEquals(0, a.trackedTiles()); assertEquals(0, a.revealedCells());
        Uniform again = a.forDraw(key(0, 0, 0), 0, 0, 16, 500, 500, late + SEC);   // dimension change / reconnect: reveals again, new origin
        assertFalse(again.isNone());
        assertEquals(0, again.tileAge(), 1e-6); assertEquals(0, again.revealAge(), 1e-6);
    }

    @Test void neighbouringSmallestTilesInTheSameAreaAllAnimateTheyAreNotRefinementsOfEachOther() {
        // regression: with 64-block bookkeeping cells the first 16-block tile marked its whole cell and froze its neighbours
        RevealAnimator a = new RevealAnimator(P, true);
        int animated = 0;
        for (int tx = 0; tx < 8; tx++) for (int tz = 0; tz < 8; tz++)
            if (!a.forDraw(key(0, tx, tz), tx * 16.0, tz * 16.0, 16, 0, 0, 0).isNone()) animated++;
        assertEquals(64, animated, "every tile of the initial burst animates");
        assertTrue(a.forDraw(key(0, 100, 100), 1600, 1600, 16, 0, 0, SEC).tileAge() == 0f, "and brand-new ground still starts fresh");
    }

    @Test void coarseTilesAreRememberedAsRectanglesSoTheirChildrenDoNotReplayTheEffect() {
        RevealAnimator a = new RevealAnimator(P, true);
        assertFalse(a.forDraw(key(9, 0, 0), 0, 0, 8192, 0, 0, 0).isNone());       // 512x512 cells > cap: stored as a rectangle, not as cells
        assertEquals(0, a.revealedCells()); assertEquals(1, a.coarseRects());
        assertTrue(a.forDraw(key(6, 3, 3), 3072, 3072, 1024, 0, 0, SEC).isNone(), "a finer tile inside the revealed coarse tile is a refinement");
        assertTrue(a.forDraw(key(0, 5, 5), 80, 80, 16, 0, 0, SEC).isNone());
        assertFalse(a.forDraw(key(9, 1, 0), 8192, 0, 8192, 0, 0, SEC).isNone(), "the neighbouring coarse tile is new ground");
    }

    @Test void disabledAnimatorNeverAnimatesAndBookkeepingStaysBounded() {
        RevealAnimator off = new RevealAnimator(P, false);
        assertTrue(off.forDraw(key(0, 0, 0), 0, 0, 16, 0, 0, 0).isNone());
        assertFalse(off.armed());
        RevealAnimator a = new RevealAnimator(P, true);
        assertFalse(a.forDraw(key(5, 0, 0), 0, 0, 512, 0, 0, 0).isNone());        // 32x32 = 1024 cells = the cap: recorded as cells
        assertEquals(1024, a.revealedCells());
        for (int i = 0; i < RevealAnimator.MAX_TRACKED_TILES + 10; i++) a.forDraw(key(0, i, 1_000_000), i * 16.0, 1.6e7, 16, 0, 0, SEC);
        assertTrue(a.trackedTiles() <= RevealAnimator.MAX_TRACKED_TILES, "bookkeeping stays bounded: " + a.trackedTiles());
        assertTrue(a.revealedCells() <= RevealAnimator.MAX_REVEALED_CELLS + 1024);
    }
}
