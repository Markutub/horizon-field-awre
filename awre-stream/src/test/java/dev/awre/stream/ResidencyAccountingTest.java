package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ClientSession;
import dev.awre.protocol.Provenance;
import dev.awre.protocol.TileKey;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import dev.horizonfield.core.terrain.TerrainMesher;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Why {@code mesh_built} and {@code gpu_uploaded} are not expected to match (the "built - uploaded = 4" observation of the CI render
 * line, reproduced with the real server -> client -> view -> planner chain in tools/bench/ResidencySim.java): {@code mesh_built} counts every
 * mesh the background thread finished, {@code gpu_uploaded} only those the plan still wanted when the upload slot came. These tests pin the
 * three situations the new {@code built_not_wanted} / {@code built_waiting} counters separate.
 */
class ResidencyAccountingTest {
    private static final String DIM = "minecraft:overworld";
    private static TileKey key(int lod, int tx, int tz) { return new TileKey(1, 2, DIM, tx, tz, lod); }

    private static final class Gpu implements MeshResidency.Backend<Integer> {
        int next = 1; final Set<Integer> live = new HashSet<>();
        @Override public Integer upload(TileKey k, TerrainMesher.Mesh m) { live.add(next); return next++; }
        @Override public void free(Integer h) { if (!live.remove(h)) throw new IllegalStateException("double free " + h); }
    }

    private static final class Store implements ClientHorizonView.TileSource {
        final Map<TileKey, ClientSession.Received> tiles = new HashMap<>();
        @Override public Set<TileKey> keys() { return new HashSet<>(tiles.keySet()); }
        @Override public ClientSession.Received tile(TileKey k) { return tiles.get(k); }
        @Override public List<String> palette(TileKey k) { return List.of("minecraft:air", "minecraft:stone"); }
        void put(int lod, int tx, int tz, int h) {
            short[] top = new short[256], mat = new short[256];
            java.util.Arrays.fill(top, (short) h); java.util.Arrays.fill(mat, (short) 1);
            tiles.put(key(lod, tx, tz), new ClientSession.Received(1, Provenance.values()[0], LodTileCodec.encode(new LodTile(new LodKey(lod, tx, tz), top, mat, Confidence.OBSERVED))));
        }
    }

    private static HorizonDrawPlanner<Integer> planner(Store s, MeshResidency.Limits l) {
        return new HorizonDrawPlanner<>(new ClientHorizonView(s, 1000, 1000, Runnable::run), new MeshResidency<>(new Gpu(), l));
    }

    private static final MeshResidency.Limits BIG = new MeshResidency.Limits(1000, 1L << 30, 1000, 1L << 30);

    private static int field(String line, String name) {
        Matcher m = Pattern.compile(" " + name + "=(\\d+)").matcher(line);
        assertTrue(m.find(), name + " missing in: " + line);
        return Integer.parseInt(m.group(1));
    }

    /** The camera (or render distance) changes between "mesh scheduled" and "upload slot": built, never wanted again, never uploaded. */
    @Test void aMeshFinishedForAPlanThatChangedBeforeTheUploadIsBuiltButNeverUploaded() {
        Store s = new Store();
        s.put(0, 10, 0, 64);                                            // x 160..176
        HorizonDrawPlanner<Integer> p = planner(s, BIG);
        p.tick(0, 0, 0, 5000, null);                                    // wanted, built (synchronous executor), but no frame yet
        p.tick(168, 0, 100, 5000, null);                                // now the vanilla square [68,268] covers it entirely
        p.frame(168, 80, 0);
        String line = p.diagnosticsLine();
        assertEquals(1, field(line, "mesh_built"));
        assertEquals(0, field(line, "gpu_uploaded"), "never wanted again, so never uploaded: " + line);
        assertEquals(1, field(line, "built_not_wanted"));
        assertEquals(0, field(line, "built_waiting"));
        assertEquals(1, field(line, "built_not_resident"));
        assertTrue(line.contains("sample=[0/10/0]"), line);
        p.close();
    }

    /** A wanted mesh that waits for the bounded upload budget is the only kind that would be a defect if it never drained. */
    @Test void wantedMeshesWaitingForTheUploadBudgetAreCountedAsWaitingAndDrain() {
        Store s = new Store();
        for (int i = 0; i < 5; i++) s.put(0, 10 + i, 0, 64);
        HorizonDrawPlanner<Integer> p = planner(s, new MeshResidency.Limits(1000, 1L << 30, 1, 1L << 30));   // one upload per sync
        p.tick(0, 0, 0, 5000, null); p.frame(0, 80, 0);
        String line = p.diagnosticsLine();
        assertTrue(field(line, "built_waiting") > 0, line);
        assertEquals(0, field(line, "built_not_wanted"), line);
        assertEquals(field(line, "built_waiting"), field(line, "built_not_resident"));
        for (int i = 0; i < 10; i++) { p.tick(0, 0, 0, 5000, null); p.frame(0, 80, 0); }
        line = p.diagnosticsLine();
        assertEquals(0, field(line, "built_waiting"), "the backlog drains: " + line);
        assertEquals(5, field(line, "gpu_uploaded"));
        p.close();
    }

    /** After a render-distance increase the freed tile's mesh stays cached on the CPU: built == uploaded, yet built_not_resident > 0. */
    @Test void aCachedMeshOfATileThatBecameHiddenIsNotAGap() {
        Store s = new Store();
        s.put(0, 10, 0, 64);
        HorizonDrawPlanner<Integer> p = planner(s, BIG);
        p.tick(168, 0, 0, 5000, null); p.frame(168, 80, 0);                // drawn, resident
        assertEquals(1, field(p.diagnosticsLine(), "gpu_resident"));
        p.tick(168, 0, 200, 5000, null); p.frame(168, 80, 0);              // render distance grew: vanilla covers the tile
        String line = p.diagnosticsLine();
        assertEquals(field(line, "mesh_built"), field(line, "gpu_uploaded"), "no gap in the lifetime counters: " + line);
        assertEquals(1, field(line, "gpu_freed"));
        assertEquals(0, field(line, "gpu_resident"));
        assertEquals(1, field(line, "built_not_wanted"), "kept as a CPU cache: " + line);
        assertEquals(0, field(line, "built_waiting"));
        p.close();
    }
}
