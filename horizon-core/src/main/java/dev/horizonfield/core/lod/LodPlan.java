package dev.horizonfield.core.lod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An immutable set of quadtree leaves that tiles the disc around a point exactly once, plus the split nodes above them. */
public final class LodPlan {
    private final List<LodKey> leaves;
    private final Set<LodKey> leafSet;
    private final Set<LodKey> splitSet;
    private final double px, pz, radius;

    LodPlan(List<LodKey> leaves, double px, double pz, double radius) {
        this.leaves = List.copyOf(leaves);
        this.leafSet = new HashSet<>(leaves);
        Set<LodKey> split = new HashSet<>();
        for (LodKey k : leaves) { LodKey p = k.parent(); while (p.lod() <= 16 && split.add(p)) p = p.parent(); }
        this.splitSet = split;
        this.px = px; this.pz = pz; this.radius = radius;
    }

    public List<LodKey> leaves() { return leaves; }
    public boolean isLeaf(LodKey k) { return leafSet.contains(k); }
    /** True if k has at least one planned leaf strictly inside it (i.e. it was refined). */
    public boolean isSplit(LodKey k) { return splitSet.contains(k); }
    public double centerX() { return px; }
    public double centerZ() { return pz; }
    public double radius() { return radius; }

    public int[] countByLevel(int maxLod) {
        int[] c = new int[maxLod + 1];
        for (LodKey k : leaves) c[k.lod()]++;
        return c;
    }

    /** Leaves in this plan that the previous plan did not have (what a client would have to fetch if it held exactly prev). */
    public List<LodKey> addedSince(LodPlan prev) {
        List<LodKey> out = new ArrayList<>();
        for (LodKey k : leaves) if (prev == null || !prev.isLeaf(k)) out.add(k);
        return out;
    }
}
