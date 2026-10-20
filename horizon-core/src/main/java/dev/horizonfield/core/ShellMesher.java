package dev.horizonfield.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Candidate representation B ("skyline shell"): turns visible cells into a bounded set of merged prisms,
 * ranked by approximate projected solid angle. This does NOT render anything by itself: a vanilla client can only
 * show prisms through some delivery mechanism (e.g. Display entities) which is gated by experiments E1/E2.
 */
public final class ShellMesher {
    private ShellMesher() { }

    public static List<ShellPrism> mesh(List<VisibleCell> cells, VisibilityParams q, int maxPrisms, int quantizeY) {
        if (quantizeY < 1) throw new IllegalArgumentException("quantizeY");
        List<int[]> rects = new ArrayList<>(cells.size());
        for (VisibleCell c : cells) {
            int s = c.sizeBlocks();
            int yl = Math.floorDiv(c.yLow(), quantizeY) * quantizeY;
            int yh = -Math.floorDiv(-c.yHigh(), quantizeY) * quantizeY;
            rects.add(new int[]{c.x0(), c.z0(), c.x0() + s, c.z0() + s, yl, yh, c.material()});
        }
        // pass 1: merge along X (same z-span, y-span, material, touching in x)
        rects.sort(Comparator.<int[]>comparingInt(a -> a[6]).thenComparingInt(a -> a[4]).thenComparingInt(a -> a[5])
                .thenComparingInt(a -> a[1]).thenComparingInt(a -> a[3]).thenComparingInt(a -> a[0]));
        List<int[]> rowMerged = new ArrayList<>();
        for (int[] r : rects) {
            int[] last = rowMerged.isEmpty() ? null : rowMerged.get(rowMerged.size() - 1);
            if (last != null && last[6] == r[6] && last[4] == r[4] && last[5] == r[5] && last[1] == r[1] && last[3] == r[3] && last[2] == r[0]) {
                last[2] = r[2];
            } else rowMerged.add(r.clone());
        }
        // pass 2: merge along Z (same x-span, y-span, material, touching in z)
        rowMerged.sort(Comparator.<int[]>comparingInt(a -> a[6]).thenComparingInt(a -> a[4]).thenComparingInt(a -> a[5])
                .thenComparingInt(a -> a[0]).thenComparingInt(a -> a[2]).thenComparingInt(a -> a[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : rowMerged) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last[6] == r[6] && last[4] == r[4] && last[5] == r[5] && last[0] == r[0] && last[2] == r[2] && last[3] == r[1]) {
                last[3] = r[3];
            } else merged.add(r.clone());
        }
        List<ShellPrism> prisms = new ArrayList<>(merged.size());
        for (int[] m : merged) {
            double cx = (m[0] + m[2]) / 2.0, cz = (m[1] + m[3]) / 2.0, cy = (m[4] + m[5]) / 2.0;
            double d2 = Math.max(1.0, sq(cx - q.eyeX()) + sq(cz - q.eyeZ()) + sq(cy - q.eyeY()));
            // visible face area estimate: sides + top, divided by distance^2 (proxy for projected solid angle)
            double h = m[5] - m[4], w = m[2] - m[0], dpt = m[3] - m[1];
            double area = h * (w + dpt) + w * dpt * 0.25;
            prisms.add(new ShellPrism(m[0], m[1], m[2], m[3], m[4], m[5], m[6], area / d2));
        }
        prisms.sort(Comparator.comparingDouble(ShellPrism::score).reversed());
        return prisms.size() > maxPrisms ? new ArrayList<>(prisms.subList(0, maxPrisms)) : prisms;
    }

    private static double sq(double v) { return v * v; }
}
