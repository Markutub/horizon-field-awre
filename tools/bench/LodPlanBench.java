import dev.horizonfield.core.lod.*;
import dev.horizonfield.core.stream.*;

/** Theory-vs-implementation numbers for the LOD planner (NOT a measurement of real terrain: kappa is an assumption). */
public class LodPlanBench {
    public static void main(String[] a) {
        LodModel m = LodModel.defaults();
        LodPlanner p = new LodPlanner(m);
        System.out.printf("K=%.1f px/block@1  ranges d_L (blocks):", m.kFactor());
        for (int l = 1; l <= m.maxLod(); l++) System.out.printf(" L%d=%.0f", l, m.splitDistance(l));
        System.out.println();
        for (double r : new double[]{2000, 4000, 8000, 12000}) {
            long t0 = System.nanoTime();
            LodPlan plan = p.plan(0, 0, r, null);
            double ms = (System.nanoTime() - t0) / 1e6;
            int[] c = plan.countByLevel(m.maxLod());
            double flatTiles = Math.PI * r * r / (m.tileSize(0) * m.tileSize(0));
            System.out.printf("R=%5.0f leaves=%5d (uniform LOD0 would need %.0f, ratio %.0fx) plan ms=%.1f  per level:", r, plan.leaves().size(), flatTiles, flatTiles / plan.leaves().size(), ms);
            for (int l = 0; l <= m.maxLod(); l++) System.out.print(" L" + l + "=" + c[l]);
            System.out.println();
        }
        // walking at 5.6 blocks/s (sprint) vs elytra ~ 30 blocks/s : new leaves needed per second, hysteresis on/off
        for (double speed : new double[]{5.6, 30.0}) {
            for (double h : new double[]{0.0, 0.15}) {
                LodPlanner pl = new LodPlanner(new LodModel(70, 1080, 2.0, 0.5, 16, 6, h, 0, 1.0));
                LodPlan prev = null; int added = 0; int steps = 600; // 30 s at 20 tps
                for (int i = 0; i < steps; i++) {
                    LodPlan cur = pl.plan(i * speed / 20.0, 0, 4000, prev);
                    if (prev != null) added += cur.addedSince(prev).size();
                    prev = cur;
                }
                System.out.printf("speed %.1f b/s hysteresis %.2f: %d new leaves in 30 s (%.1f/s)%n", speed, h, added, added / 30.0);
            }
        }
    }
}
