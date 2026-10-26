package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class DrainGovernorTest {
    private static final long BUDGET = 500_000L;                 // 0.5 ms per tick, like the capture drain
    private static final int PER_TICK_CAP = 2;                   // MAX_PER_TICK in AwreCapture

    private static final class Run { long charged, jobs; }

    /** An endless backlog drained for {@code ticks} ticks the way AwreCapture does it. */
    private static Run run(DrainGovernor g, int ticks, LongSupplier cost) {
        Run r = new Run();
        for (int t = 0; t < ticks; t++) {
            g.onTick();
            for (int n = 0; n < PER_TICK_CAP && g.mayStart(); n++) { long c = cost.getAsLong(); g.record(c); r.charged += Math.min(c, g.maxChargeNanos()); r.jobs++; }
        }
        return r;
    }

    @Test void cheapJobsRunAtTheHardCapAndTheAverageStaysInBudget() {
        DrainGovernor g = new DrainGovernor(BUDGET, 150_000);
        Run r = run(g, 2000, () -> 100_000);                                   // p50 of the CI snapshot: 0.1 ms
        assertEquals(2 * 2000, r.jobs, 2 * 2000 * 0.02, "two per tick when each costs 0.1 ms: " + r.jobs);
        assertTrue(r.charged <= 2000L * BUDGET + g.maxCreditNanos() + g.maxChargeNanos());
    }

    @Test void expensiveJobsStillMakeProgressAboutOncePerCostOverBudgetTicks() {
        DrainGovernor g = new DrainGovernor(BUDGET, 150_000);
        Run r = run(g, 8000, () -> 4_000_000L);                                // 4 ms each: one every 8 ticks
        assertTrue(r.jobs >= 950 && r.jobs <= 1010, "expected about 1000 jobs, got " + r.jobs);
        assertTrue(r.charged <= 8000L * BUDGET + g.maxCreditNanos() + g.maxChargeNanos(), "charged " + r.charged);
    }

    @Test void aHeavyTailedCostDistributionIsBoundedOnAverage() {
        Random rnd = new Random(7);
        DrainGovernor g = new DrainGovernor(BUDGET, 150_000);
        // p50 0.1 ms, 5% at 3-6 ms: close to the shape measured in CI (p50 0.10, p95 0.63, p99 4.45, max 5.49 ms)
        Run r = run(g, 20_000, () -> rnd.nextInt(100) < 5 ? 3_000_000L + rnd.nextInt(3_000_000) : 80_000L + rnd.nextInt(60_000));
        assertTrue(r.charged <= 20_000L * BUDGET + g.maxCreditNanos() + g.maxChargeNanos(), "charged " + r.charged + " vs allowed " + (20_000L * BUDGET));
        assertTrue(r.jobs > 15_000, "and it still does a lot of work: " + r.jobs);
    }

    @Test void oneHugeOutlierCostsAtMostABoundedNumberOfIdleTicks() {
        DrainGovernor g = new DrainGovernor(BUDGET, 150_000);
        for (int i = 0; i < 50; i++) { g.onTick(); if (g.mayStart()) g.record(100_000); }
        g.onTick(); g.record(200_000_000L);                                    // a 200 ms GC pause counted inside one job
        int idle = 0;
        while (true) { g.onTick(); if (g.mayStart()) break; if (++idle > 1000) fail("starved"); }
        long bound = (g.maxChargeNanos() + g.maxCreditNanos()) / BUDGET + 1;
        assertTrue(idle <= bound, "locked out for " + idle + " ticks, bound " + bound);
    }

    @Test void idleTimeCannotBeBankedIntoABurst() {
        DrainGovernor g = new DrainGovernor(BUDGET, 1_000_000);
        for (int i = 0; i < 100_000; i++) g.onTick();
        assertEquals(g.maxCreditNanos(), g.creditNanos());
        int started = 0;
        for (int i = 0; i < 100 && g.mayStart(); i++) { g.record(1_000_000); started++; }   // 1 ms jobs, no per-tick cap here
        assertTrue(started <= g.maxCreditNanos() / 1_000_000 + 1, "burst of " + started);
    }

    @Test void theExpectedCostAdaptsDownwardsSoWorkResumesAtFullSpeedAfterAnExpensivePhase() {
        DrainGovernor g = new DrainGovernor(BUDGET, 150_000);
        run(g, 400, () -> 5_000_000L);
        assertTrue(g.expectedCostNanos() > 3_000_000);
        Run r = run(g, 400, () -> 50_000L);
        assertTrue(g.expectedCostNanos() < 200_000, "estimate " + g.expectedCostNanos());
        assertTrue(r.jobs > 700, "back near two per tick: " + r.jobs);
    }

    @Test void theBacklogDrainOverloadWithAGovernorHandlesKeysUntilTheCreditIsSpent() {
        CaptureBacklog<Integer> b = new CaptureBacklog<>(64);
        for (int i = 0; i < 50; i++) b.request(i);
        b.pump(k -> k);
        DrainGovernor g = new DrainGovernor(BUDGET, 2_000_000);                // expected cost 2 ms per job, credit starts at one tick (0.5 ms)
        assertEquals(0, b.drain(k -> true, g::mayStart, 100), "0.5 ms of credit does not cover a 2 ms job: nothing starts");
        assertEquals(50, b.queued(), "and nothing was popped and lost or re-queued");
        g.onTick(); g.onTick(); g.onTick();                                    // credit 2 ms
        int n = b.drain(k -> { g.record(2_000_000); return true; }, g::mayStart, 100);
        assertEquals(1, n, "exactly one job fits the credit");
        assertEquals(49, b.queued());
        assertEquals(1, b.stats().handled());
    }

    @Test void invalidBudgetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DrainGovernor(0, 0));
    }
}
