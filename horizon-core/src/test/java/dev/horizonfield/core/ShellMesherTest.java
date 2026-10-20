package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShellMesherTest {
    private static final VisibilityParams Q = new VisibilityParams(0, 70, 0, 16, 500, 64, 0);

    @Test
    void adjacentEqualCellsMergeIntoOnePrism() {
        List<VisibleCell> cells = new ArrayList<>();
        for (int x = 4; x < 8; x++) for (int z = 4; z < 6; z++) cells.add(new VisibleCell(0, x, z, 60, 80, 1, Confidence.OBSERVED));
        List<ShellPrism> prisms = ShellMesher.mesh(cells, Q, 100, 4);
        assertEquals(1, prisms.size());
        ShellPrism p = prisms.get(0);
        assertEquals(64, p.x0());
        assertEquals(128, p.x1());
        assertEquals(64, p.z0());
        assertEquals(96, p.z1());
    }

    @Test
    void differentMaterialsDoNotMerge() {
        List<VisibleCell> cells = List.of(
                new VisibleCell(0, 4, 4, 60, 80, 1, Confidence.OBSERVED),
                new VisibleCell(0, 5, 4, 60, 80, 2, Confidence.OBSERVED));
        assertEquals(2, ShellMesher.mesh(cells, Q, 100, 4).size());
    }

    @Test
    void budgetKeepsHighestScoring() {
        List<VisibleCell> cells = new ArrayList<>();
        cells.add(new VisibleCell(0, 2, 0, 60, 200, 1, Confidence.OBSERVED)); // near and tall
        cells.add(new VisibleCell(0, 30, 0, 60, 62, 2, Confidence.OBSERVED)); // far and flat
        List<ShellPrism> prisms = ShellMesher.mesh(cells, Q, 1, 4);
        assertEquals(1, prisms.size());
        assertEquals(1, prisms.get(0).material());
    }
}
