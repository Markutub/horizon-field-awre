package dev.horizonfield.core.lod;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LodPlannerTest {
    private final LodModel m = LodModel.defaults();
    private final LodPlanner planner = new LodPlanner(m);

    private static boolean inside(LodModel m, LodKey k, double x, double z) {
        double s = m.tileSize(k.lod());
        return x >= k.tx() * s && x < (k.tx() + 1) * s && z >= k.tz() * s && z < (k.tz() + 1) * s;
    }

    private double dist(LodKey k, double px, double pz) {
        double s = m.tileSize(k.lod()), minX = k.tx() * s, minZ = k.tz() * s;
        double dx = Math.max(Math.max(minX - px, 0), px - (minX + s)), dz = Math.max(Math.max(minZ - pz, 0), pz - (minZ + s));
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Test void formulaMatchesHandCalculation() {
        // K = H / (2 tau tan(fov/2)) = 1080 / (2*2*tan 35deg) ~ 385.6 ; d_1 = kappa*(2-1)*K ~ 192.8
        assertTrue(Math.abs(m.kFactor() - 385.6) < 0.5, "K=" + m.kFactor());
        assertTrue(Math.abs(m.splitDistance(1) - 192.8) < 0.5, "d1=" + m.splitDistance(1));
        assertTrue(Math.abs(m.splitDistance(2) - 3 * 192.8) < 1.0);
        assertTrue(m.splitDistance(0) == 0.0);
        for (int l = 1; l < m.maxLod(); l++) assertTrue(m.splitDistance(l + 1) > m.splitDistance(l), "ranges must grow");
        // by construction the projected error at the split distance equals the tolerance
        assertTrue(Math.abs(m.projectedPx(3, m.splitDistance(3)) - m.tolerancePx()) < 1e-6);
    }

    @Test void everyPointInTheDiscIsCoveredExactlyOnce() {
        double px = 123.4, pz = -567.8, r = 3000;
        LodPlan plan = planner.plan(px, pz, r, null);
        Random rnd = new Random(1);
        for (int i = 0; i < 4000; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * r;
            double x = px + Math.cos(a) * d, z = pz + Math.sin(a) * d;
            int n = 0;
            for (LodKey k : plan.leaves()) if (inside(m, k, x, z)) n++;
            assertEquals(1, n, "point " + x + "," + z);
        }
    }

    @Test void neighbouringLeavesDifferByAtMostOneLevel() {
        for (double[] p : new double[][]{{0, 0}, {777.7, 31.1}, {-4000.5, 2500.25}}) {
            LodPlan plan = planner.plan(p[0], p[1], 6000, null);
            for (LodKey k : plan.leaves()) {
                double s = m.tileSize(k.lod()), x0 = k.tx() * s, z0 = k.tz() * s;
                for (double t = 0.5; t < s; t += m.tileSize(0)) {
                    for (double[] q : new double[][]{{x0 + t, z0 - 0.5}, {x0 + t, z0 + s + 0.5}, {x0 - 0.5, z0 + t}, {x0 + s + 0.5, z0 + t}}) {
                        LodKey n = planner.leafAt(plan, q[0], q[1]);
                        if (n != null) assertTrue(Math.abs(n.lod() - k.lod()) <= 1, k + " next to " + n);
                    }
                }
            }
        }
    }

    @Test void noLeafExceedsThePixelToleranceWithoutHysteresis() {
        double px = 55.5, pz = 12.25;
        LodPlan plan = planner.plan(px, pz, 9000, null);
        for (LodKey k : plan.leaves()) {
            if (k.lod() == 0) continue;
            double d = dist(k, px, pz);
            assertTrue(m.projectedPx(k.lod(), d) <= m.tolerancePx() + 1e-9, k + " has " + m.projectedPx(k.lod(), d) + " px at " + d);
        }
    }

    @Test void leafCountsPerLevelFollowTheAnnulusAreaPrediction() {
        double r = 8000;
        LodPlan plan = planner.plan(0, 0, r, null);
        int[] c = plan.countByLevel(m.maxLod());
        StringBuilder sb = new StringBuilder();
        for (int l = 1; l < m.maxLod(); l++) {
            double lo = m.splitDistance(l), hi = Math.min(m.splitDistance(l + 1), r);
            if (hi <= lo) continue;
            double predicted = Math.PI * (hi * hi - lo * lo) / (m.tileSize(l) * m.tileSize(l));
            double ratio = c[l] / predicted;
            sb.append(" L").append(l).append(" actual=").append(c[l]).append(" predicted=").append(Math.round(predicted)).append(" ratio=").append(String.format("%.2f", ratio));
            assertTrue(ratio > 0.4 && ratio < 2.5, "level " + l + sb);
        }
    }

    @Test void hysteresisCutsChurnWhenTheViewerJitters() {
        LodModel noH = new LodModel(70, 1080, 2.0, 0.5, 16, 6, 0.0, 0, 1.0);
        LodModel withH = LodModel.defaults();
        int churn0 = churn(new LodPlanner(noH)), churnH = churn(new LodPlanner(withH));
        assertTrue(churn0 > 0, "test must exercise boundaries, churn0=" + churn0);
        assertTrue(churnH * 3 <= churn0, "with=" + churnH + " without=" + churn0);
    }

    private int churn(LodPlanner p) {
        LodPlan prev = null;
        int total = 0;
        for (int i = 0; i < 120; i++) {
            double x = 500 + (i % 2 == 0 ? 0 : 45);   // +/-45-block jitter across level boundaries
            LodPlan cur = p.plan(x, 0, 4000, prev);
            if (prev != null) total += cur.addedSince(prev).size();
            prev = cur;
        }
        return total;
    }

    @Test void planningIsDeterministic() {
        List<LodKey> a = planner.plan(10, 20, 5000, null).leaves(), b = planner.plan(10, 20, 5000, null).leaves();
        assertEquals(a, b);
    }

    @Test void rejectsAbsurdInputs() {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(0, 0, 900_000, null));
        assertThrows(IllegalArgumentException.class, () -> new LodModel(70, 1080, 2, 0.5, 15, 6, 0.1, 0, 1));
    }
}
