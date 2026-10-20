package dev.horizonfield.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record VisibilityResult(List<VisibleCell> cells, long probes, long unknownProbes, long nanos) {

    /** Expands every visible cell to level-0 chunk keys ({@link HeightPyramid#key}). Coarse cells expand to all contained chunks. */
    public Set<Long> toChunkSet() {
        Set<Long> out = new HashSet<>();
        for (VisibleCell c : cells) {
            int n = 1 << c.level();
            for (int dz = 0; dz < n; dz++) {
                for (int dx = 0; dx < n; dx++) out.add(HeightPyramid.key(c.cx() * n + dx, c.cz() * n + dz));
            }
        }
        return out;
    }
}
