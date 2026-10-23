package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The server's per-player tile budget, the client's mesh cap, GPU residency and the client tile cache must agree (G2 audit). */
class BudgetContractTest {
    @Test void theServerDefaultNeverExceedsWhatTheClientCanMesh() {
        assertTrue(StreamConfig.defaults().maxHeldTiles() <= StreamConfig.CLIENT_MESH_CAP);
    }

    @Test void gpuResidencyCanHoldEveryMeshTheViewMayBuild() {
        assertTrue(MeshResidency.Limits.defaults().maxResident() >= StreamConfig.CLIENT_MESH_CAP,
                "the view may build " + StreamConfig.CLIENT_MESH_CAP + " meshes; residency keeps " + MeshResidency.Limits.defaults().maxResident());
    }
}
