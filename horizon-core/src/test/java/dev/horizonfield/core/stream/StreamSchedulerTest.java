package dev.horizonfield.core.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.lod.LodModel;
import dev.horizonfield.core.lod.LodPlan;
import dev.horizonfield.core.lod.LodPlanner;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StreamSchedulerTest {
    private final LodModel m = LodModel.defaults();
    private final LodPlanner planner = new LodPlanner(m);
    private final StreamScheduler.TileSource src = new StreamScheduler.TileSource() {
        @Override public int estimatedBytes(LodKey k) { return 2048; }
        @Override public long revision(LodKey k) { return 1; }
    };

    private static void deliver(StreamScheduler s, StreamScheduler.Decision d) {
        for (StreamScheduler.Order o : d.send()) { s.onSent(o.key(), 1); s.onAck(o.key(), 1, true); }
        for (LodKey k : d.evict()) s.onEvicted(k);
    }

    private boolean pointHeld(Map<LodKey, Long> held, double x, double z) {
        for (LodKey k : held.keySet()) {
            double s = m.tileSize(k.lod());
            if (x >= k.tx() * s && x < (k.tx() + 1) * s && z >= k.tz() * s && z < (k.tz() + 1) * s) return true;
        }
        return false;
    }

    @Test void coldStartSendsEverythingWithAnInfiniteBudgetAndNearestFirst() {
        StreamScheduler s = new StreamScheduler(m);
        LodPlan plan = planner.plan(0, 0, 3000, null);
        StreamScheduler.Decision d = s.decide(plan, 0, 0, 0, Long.MAX_VALUE / 4, 1_000_000, src);
        assertEquals(plan.leaves().size(), d.send().size());
        assertEquals(plan.leaves().size(), d.holes());
        assertTrue(d.evict().isEmpty());
        // the very first order must be a fine tile close to the player
        LodKey first = d.send().get(0).key();
        assertTrue(first.lod() <= 2, "first=" + first);
        double prev = Double.MAX_VALUE;
        for (StreamScheduler.Order o : d.send()) { assertTrue(o.priority() <= prev + 1e-12); prev = o.priority(); }
    }

    @Test void budgetIsNeverExceeded() {
        StreamScheduler s = new StreamScheduler(m);
        LodPlan plan = planner.plan(0, 0, 3000, null);
        StreamScheduler.Decision d = s.decide(plan, 0, 0, 0, 10_000, 1000, src);
        long sum = 0;
        for (StreamScheduler.Order o : d.send()) sum += o.bytes();
        assertTrue(sum <= 10_000 && d.send().size() == 4, "sum=" + sum + " n=" + d.send().size());
        assertTrue(s.decide(plan, 0, 0, 0, 0, 1000, src).send().isEmpty());
        assertEquals(3, s.decide(plan, 0, 0, 0, 1_000_000, 3, src).send().size());
    }

    @Test void convergedClientNeedsNothing() {
        StreamScheduler s = new StreamScheduler(m);
        LodPlan plan = planner.plan(0, 0, 2500, null);
        deliver(s, s.decide(plan, 0, 0, 0, Long.MAX_VALUE / 4, 1_000_000, src));
        StreamScheduler.Decision d = s.decide(plan, 0, 0, 0, Long.MAX_VALUE / 4, 1_000_000, src);
        assertTrue(d.send().isEmpty() && d.evict().isEmpty() && d.holes() == 0);
    }

    @Test void pendingTilesAreNotResentUntilLost() {
        StreamScheduler s = new StreamScheduler(m);
        LodPlan plan = planner.plan(0, 0, 1500, null);
        long all = Long.MAX_VALUE / 4;
        StreamScheduler.Decision d1 = s.decide(plan, 0, 0, 0, all, 1_000_000, src);
        assertEquals(plan.leaves().size(), d1.send().size());
        for (StreamScheduler.Order o : d1.send()) s.onSent(o.key(), 1);
        assertTrue(s.decide(plan, 0, 0, 0, all, 1_000_000, src).send().isEmpty());
        s.onLost(d1.send().get(0).key());
        assertEquals(1, s.decide(plan, 0, 0, 0, all, 1_000_000, src).send().size());
    }

    @Test void outdatedRevisionIsResent() {
        StreamScheduler s = new StreamScheduler(m);
        LodPlan plan = planner.plan(0, 0, 1500, null);
        deliver(s, s.decide(plan, 0, 0, 0, Long.MAX_VALUE / 4, 1_000_000, src));
        LodKey target = plan.leaves().get(0);
        StreamScheduler.TileSource edited = new StreamScheduler.TileSource() {
            @Override public int estimatedBytes(LodKey k) { return 2048; }
            @Override public long revision(LodKey k) { return k.equals(target) ? 2 : 1; }
        };
        StreamScheduler.Decision d = s.decide(plan, 0, 0, 0, 1_000_000, 1000, edited);
        assertEquals(1, d.send().size());
        assertEquals(target, d.send().get(0).key());
        assertEquals(1, d.stale());
    }

    @Test void evictionNeverCreatesAHoleEvenWhenStarved() {
        // Walk 60 steps of 120 blocks with a budget of ONE tile per step: far below what the plan needs.
        StreamScheduler s = new StreamScheduler(m);
        Random rnd = new Random(5);
        LodPlan prev = null;
        double x = 0, z = 0, heading = 0;
        for (int step = 0; step < 60; step++) {
            x += Math.cos(heading) * 120; z += Math.sin(heading) * 120;
            if (step % 10 == 9) heading += 1.2;
            LodPlan plan = planner.plan(x, z, 2500, prev);
            StreamScheduler.Decision d = s.decide(plan, x, z, heading, 2048, 1, src);
            Map<LodKey, Long> before = s.heldSnapshot();
            for (StreamScheduler.Order o : d.send()) { s.onSent(o.key(), 1); s.onAck(o.key(), 1, true); }
            for (LodKey k : d.evict()) s.onEvicted(k);
            Map<LodKey, Long> after = s.heldSnapshot();
            for (int i = 0; i < 400; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, dd = Math.sqrt(rnd.nextDouble()) * 2400;
                double qx = x + Math.cos(a) * dd, qz = z + Math.sin(a) * dd;
                if (pointHeld(before, qx, qz)) assertTrue(pointHeld(after, qx, qz), "step " + step + ": eviction opened a hole at " + qx + "," + qz);
            }
            prev = plan;
        }
    }

    @Test void convergesWhenTheViewerStopsWithAFiniteBudget() {
        StreamScheduler s = new StreamScheduler(m);
        LodPlan plan = null;
        double x = 0, z = 0;
        for (int step = 0; step < 40; step++) { x += 90; plan = planner.plan(x, z, 2500, plan); deliver(s, s.decide(plan, x, z, 0, 40_000, 1000, src)); }
        int guard = 0;
        StreamScheduler.Decision d;
        do {
            plan = planner.plan(x, z, 2500, plan);
            d = s.decide(plan, x, z, 0, 40_000, 1000, src);
            deliver(s, d);
        } while ((d.holes() > 0 || !d.send().isEmpty() || !d.evict().isEmpty()) && ++guard < 200);
        assertTrue(guard < 200, "did not converge");
        Set<LodKey> held = new HashSet<>(s.heldSnapshot().keySet());
        assertEquals(new HashSet<>(plan.leaves()), held);
    }

    @Test void finerTilesAreConsolidatedOnlyAfterRealNeedsAreServed() {
        StreamScheduler s = new StreamScheduler(m);
        LodPlan near = planner.plan(0, 0, 2500, null);
        deliver(s, s.decide(near, 0, 0, 0, Long.MAX_VALUE / 4, 1_000_000, src));
        LodPlan far = planner.plan(6000, 0, 2500, near);   // viewer moved away: old fine tiles are out of range
        StreamScheduler.Decision d = s.decide(far, 6000, 0, 0, Long.MAX_VALUE / 4, 1_000_000, src);
        assertTrue(d.holes() > 0);
        assertTrue(d.evict().size() > 0, "tiles outside the plan may be dropped");
    }

    @Test void byteBudgetRefillsWithTheClock() {
        AtomicLong t = new AtomicLong(0);
        ByteBudget b = new ByteBudget(10_000, 1000, t::get);
        assertTrue(b.tryTake(10_000));
        assertFalse(b.tryTake(1));
        t.addAndGet(2_000_000_000L);   // 2 s -> 2000 bytes
        assertEquals(2000L, b.available());
        assertTrue(b.tryTake(2000));
        t.addAndGet(1_000_000_000_000L);
        assertEquals(10_000L, b.available());   // capped at the burst
    }

    @Test void inFlightWindowFollowsLittlesLaw() {
        // 1 MB/s, 100 ms RTT, 16 KiB tiles: 1e6/16384*0.1 = 6.1 tiles in the pipe -> ceil 7 + 1
        assertEquals(8, InFlightSizer.size(1_000_000, 0.1, 16384, 1, 64));
        assertEquals(64, InFlightSizer.size(1e9, 1.0, 100, 1, 64));
        assertEquals(2, InFlightSizer.size(1000, 0.01, 16384, 2, 64));
    }
}
