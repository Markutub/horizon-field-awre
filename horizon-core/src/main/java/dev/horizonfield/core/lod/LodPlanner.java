package dev.horizonfield.core.lod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Distance-driven quadtree LOD selection with hysteresis and 2:1 edge balancing. Pure and deterministic.
 * Guarantees (all unit-tested): every point within `radius` of the centre lies in exactly one leaf; adjacent leaves
 * differ by at most one level; with hysteresis > 0 a viewer jittering by less than hysteresis*d_L never flips a node.
 */
public final class LodPlanner {
    private final LodModel m;

    public LodPlanner(LodModel model) { this.m = model; }

    public LodModel model() { return m; }

    public LodPlan plan(double px, double pz, double radius, LodPlan prev) {
        if (radius <= 0 || radius > 1_000_000) throw new IllegalArgumentException("radius");
        double rootSize = m.tileSize(m.maxLod());
        int x0 = (int) Math.floor((px - radius) / rootSize), x1 = (int) Math.floor((px + radius) / rootSize);
        int z0 = (int) Math.floor((pz - radius) / rootSize), z1 = (int) Math.floor((pz + radius) / rootSize);
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > 4096) throw new IllegalArgumentException("too many root tiles; lower radius or raise maxLod");
        List<LodKey> leaves = new ArrayList<>();
        for (int tx = x0; tx <= x1; tx++) for (int tz = z0; tz <= z1; tz++) refine(new LodKey(m.maxLod(), tx, tz), px, pz, radius, prev, leaves);
        balance(leaves);
        return new LodPlan(leaves, px, pz, radius);
    }

    private double planarDistance(LodKey k, double px, double pz) {
        double s = m.tileSize(k.lod());
        double minX = k.tx() * s, minZ = k.tz() * s;
        double dx = Math.max(Math.max(minX - px, 0), px - (minX + s));
        double dz = Math.max(Math.max(minZ - pz, 0), pz - (minZ + s));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void refine(LodKey k, double px, double pz, double radius, LodPlan prev, List<LodKey> out) {
        double planar = planarDistance(k, px, pz);
        if (planar > radius) return;                                   // culled: wholly outside the disc
        double dist = Math.sqrt(planar * planar + m.eyeHeight() * m.eyeHeight());
        boolean split;
        if (k.lod() == 0) split = false;
        else {
            double th = m.splitDistance(k.lod());
            double h = m.hysteresis();
            if (prev != null && prev.isLeaf(k)) split = dist < th * (1 - h);        // was coarse: needs to get clearly closer to refine
            else if (prev != null && prev.isSplit(k)) split = dist < th * (1 + h);  // was refined: needs to get clearly farther to merge
            else split = dist < th;
        }
        if (!split) { out.add(k); return; }
        for (LodKey c : k.children()) refine(c, px, pz, radius, prev, out);
    }

    /** Enforce |level(a) - level(b)| <= 1 for edge-adjacent leaves by splitting the coarser one (terminates: levels only decrease). */
    private void balance(List<LodKey> leaves) {
        Set<LodKey> set = new HashSet<>(leaves);
        boolean changed = true;
        while (changed) {
            changed = false;
            List<LodKey> toSplit = new ArrayList<>();
            for (LodKey k : set) {
                if (k.lod() < 2) continue;
                if (hasMuchFinerNeighbour(k, set)) toSplit.add(k);
            }
            for (LodKey k : toSplit) {
                set.remove(k);
                for (LodKey c : k.children()) set.add(c);
                changed = true;
            }
        }
        leaves.clear();
        leaves.addAll(set);
        leaves.sort((a, b) -> a.lod() != b.lod() ? Integer.compare(b.lod(), a.lod()) : a.tx() != b.tx() ? Integer.compare(a.tx(), b.tx()) : Integer.compare(a.tz(), b.tz()));
    }

    private boolean hasMuchFinerNeighbour(LodKey k, Set<LodKey> set) {
        double s = m.tileSize(k.lod()), s0 = m.tileSize(0);
        double minX = k.tx() * s, minZ = k.tz() * s;
        int n = (int) Math.round(s / s0);
        for (int i = 0; i < n; i++) {
            double t = (i + 0.5) * s0;
            if (levelAt(minX + t, minZ - 0.5, set) <= k.lod() - 2) return true;       // north
            if (levelAt(minX + t, minZ + s + 0.5, set) <= k.lod() - 2) return true;   // south
            if (levelAt(minX - 0.5, minZ + t, set) <= k.lod() - 2) return true;       // west
            if (levelAt(minX + s + 0.5, minZ + t, set) <= k.lod() - 2) return true;   // east
        }
        return false;
    }

    /** Level of the leaf containing the point, or Integer.MAX_VALUE if none (outside the planned disc). */
    private int levelAt(double x, double z, Set<LodKey> set) {
        for (int l = 0; l <= m.maxLod(); l++) {
            double s = m.tileSize(l);
            if (set.contains(new LodKey(l, (int) Math.floor(x / s), (int) Math.floor(z / s)))) return l;
        }
        return Integer.MAX_VALUE;
    }

    /** Leaf containing the point, or null. Used by tests and the scheduler. */
    public LodKey leafAt(LodPlan plan, double x, double z) {
        for (int l = 0; l <= m.maxLod(); l++) {
            double s = m.tileSize(l);
            LodKey k = new LodKey(l, (int) Math.floor(x / s), (int) Math.floor(z / s));
            if (plan.isLeaf(k)) return k;
        }
        return null;
    }
}
