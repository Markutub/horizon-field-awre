package dev.horizonfield.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Visibility-first horizon sweep. For each azimuth ray it walks the height pyramid outward, keeping a running
 * horizon angle (as tan of elevation). A node is reported only if its top can poke above that horizon.
 *
 * Conservativeness (never hide something that is truly visible, within the model):
 *  - visibility test uses the node's MAX height at its NEAR edge (largest possible elevation),
 *  - occlusion uses the node's MIN height at its FAR edge, lowered by slackBlocks, and only for COMPLETE nodes.
 * Unknown (unloaded, never observed) terrain neither occludes nor is reported.
 *
 * Known model limits: surface heightfield only (no caves/overhangs), rays are sampled (bins), not exhaustive.
 */
public final class VisibilitySweep {
    private static final double EPS = 1e-9;

    private VisibilitySweep() { }

    private record Cell(int level, int cx, int cz) { }

    private record Resolved(int level, int cx, int cz, PyramidNode node) { }

    public static VisibilityResult run(HeightPyramid pyramid, VisibilityParams q) {
        long t0 = System.nanoTime();
        long probes = 0, unknown = 0;
        double dTheta = 2 * Math.PI / q.azimuthBins();
        Map<Cell, int[]> out = new HashMap<>();

        for (int i = 0; i < q.azimuthBins(); i++) {
            double theta = (i + 0.5) * dTheta;
            double dx = Math.cos(theta), dz = Math.sin(theta);
            double horizon = Double.NEGATIVE_INFINITY;
            double r = q.minRadius();
            while (r <= q.maxRadius()) {
                if (horizon != Double.NEGATIVE_INFINITY && (pyramid.maxSeenY() + 1 - q.eyeY()) / r < horizon - EPS) break;
                int startLevel = levelFor(r, dTheta, pyramid.maxLevel());
                double px = q.eyeX() + dx * r, pz = q.eyeZ() + dz * r;
                Resolved res = resolve(pyramid, px, pz, startLevel);
                probes++;
                if (res == null) {
                    unknown++;
                    int size = 16 << startLevel;
                    // jump to the exit of the (unknown) cell of the requested level
                    r = exitDistance(q, dx, dz, Math.floorDiv((int) Math.floor(px), size), Math.floorDiv((int) Math.floor(pz), size), size) + 1e-3;
                    continue;
                }
                int size = 16 << res.level();
                double x0 = (double) res.cx() * size, z0 = (double) res.cz() * size;
                double[] span = slab(q.eyeX(), q.eyeZ(), dx, dz, x0, z0, x0 + size, z0 + size);
                double rNear = Math.max(Math.max(span[0], q.minRadius()), 1.0);
                double rFar = Math.max(span[1], rNear + 1e-3);
                PyramidNode n = res.node();

                double tanTop = (n.maxY() + 1 - q.eyeY()) / rNear;
                if (tanTop >= horizon - EPS) {
                    double yLowRay = Double.isInfinite(horizon) ? n.minY() : Math.max(n.minY(), q.eyeY() + horizon * rNear);
                    int yLow = (int) Math.floor(yLowRay);
                    int yHigh = n.maxY() + 1;
                    Cell key = new Cell(res.level(), res.cx(), res.cz());
                    int[] v = out.get(key);
                    if (v == null) out.put(key, new int[]{yLow, yHigh, n.material(), n.confidence().ordinal()});
                    else if (yLow < v[0]) v[0] = yLow;
                }
                if (n.complete()) {
                    double occ = (n.minY() + 1 - q.slackBlocks() - q.eyeY()) / rFar;
                    if (occ > horizon) horizon = occ;
                }
                r = rFar + 1e-3;
            }
        }

        ArrayList<VisibleCell> cells = new ArrayList<>(out.size());
        Confidence[] cv = Confidence.values();
        for (Map.Entry<Cell, int[]> e : out.entrySet()) {
            Cell c = e.getKey();
            int[] v = e.getValue();
            cells.add(new VisibleCell(c.level(), c.cx(), c.cz(), v[0], v[1], v[2], cv[v[3]]));
        }
        return new VisibilityResult(cells, probes, unknown, System.nanoTime() - t0);
    }

    /** Pyramid level so that node size is roughly the ray spacing at distance r. */
    static int levelFor(double r, double dTheta, int maxLevel) {
        double spacing = r * dTheta;
        if (spacing <= 16) return 0;
        int l = (int) Math.floor(Math.log(spacing / 16.0) / Math.log(2));
        return Math.max(0, Math.min(maxLevel, l));
    }

    /** Coarsest complete node at/below startLevel; falls back to a level-0 node; null if nothing is known. */
    private static Resolved resolve(HeightPyramid p, double px, double pz, int startLevel) {
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
        for (int l = startLevel; l >= 0; l--) {
            int size = 16 << l;
            int cx = Math.floorDiv(bx, size), cz = Math.floorDiv(bz, size);
            PyramidNode n = p.get(l, cx, cz);
            if (n != null && (n.complete() || l == 0)) return new Resolved(l, cx, cz, n);
        }
        return null;
    }

    private static double exitDistance(VisibilityParams q, double dx, double dz, int cx, int cz, int size) {
        double x0 = (double) cx * size, z0 = (double) cz * size;
        return slab(q.eyeX(), q.eyeZ(), dx, dz, x0, z0, x0 + size, z0 + size)[1];
    }

    /** Ray/AABB slab intersection in 2D. Returns {tEnter, tExit} (tExit>=tEnter assumed when the ray is inside/through). */
    private static double[] slab(double ox, double oz, double dx, double dz, double x0, double z0, double x1, double z1) {
        double tMinX, tMaxX, tMinZ, tMaxZ;
        if (Math.abs(dx) < 1e-12) { tMinX = Double.NEGATIVE_INFINITY; tMaxX = Double.POSITIVE_INFINITY; }
        else {
            double a = (x0 - ox) / dx, b = (x1 - ox) / dx;
            tMinX = Math.min(a, b); tMaxX = Math.max(a, b);
        }
        if (Math.abs(dz) < 1e-12) { tMinZ = Double.NEGATIVE_INFINITY; tMaxZ = Double.POSITIVE_INFINITY; }
        else {
            double a = (z0 - oz) / dz, b = (z1 - oz) / dz;
            tMinZ = Math.min(a, b); tMaxZ = Math.max(a, b);
        }
        return new double[]{Math.max(tMinX, tMinZ), Math.min(tMaxX, tMaxZ)};
    }
}
