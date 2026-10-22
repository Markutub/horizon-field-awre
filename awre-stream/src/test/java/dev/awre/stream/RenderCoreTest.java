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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure-Java render core: vertex packing, camera-relative offsets, GPU residency lifecycle and the draw plan (fake GPU backend, REAL view and mesher). */
class RenderCoreTest {
    private static final String DIM = "minecraft:overworld";
    private static TileKey key(int lod, int tx, int tz) { return new TileKey(1, 2, DIM, tx, tz, lod); }

    /** Counts what the GPU would hold. Mimics the real contract: handles are unique, a freed handle may not be freed twice. */
    private static final class FakeGpu implements MeshResidency.Backend<Integer> {
        int next = 1, uploads, frees; boolean failUploads; final Set<Integer> live = new HashSet<>(); final Map<Integer, TileKey> owner = new HashMap<>();
        @Override public Integer upload(TileKey k, TerrainMesher.Mesh m) {
            if (failUploads) throw new IllegalStateException("simulated GPU failure");
            int h = next++; live.add(h); owner.put(h, k); uploads++; return h;
        }
        @Override public void free(Integer h) { if (!live.remove(h)) throw new IllegalStateException("double free " + h); frees++; }
    }

    private static final class Store implements ClientHorizonView.TileSource {
        final Map<TileKey, ClientSession.Received> tiles = new HashMap<>();
        @Override public Set<TileKey> keys() { return new HashSet<>(tiles.keySet()); }
        @Override public ClientSession.Received tile(TileKey k) { return tiles.get(k); }
        @Override public List<String> palette(TileKey k) { return List.of("minecraft:air", "minecraft:stone"); }
        void put(int lod, int tx, int tz, long rev, int h) {
            short[] top = new short[256], mat = new short[256];
            java.util.Arrays.fill(top, (short) h); java.util.Arrays.fill(mat, (short) 1);
            tiles.put(key(lod, tx, tz), new ClientSession.Received(rev, Provenance.values()[0], LodTileCodec.encode(new LodTile(new LodKey(lod, tx, tz), top, mat, Confidence.OBSERVED))));
        }
    }

    private static HorizonDrawPlanner<Integer> planner(Store s, FakeGpu gpu, MeshResidency.Limits l) {
        return new HorizonDrawPlanner<>(new ClientHorizonView(s, 1000, 1000, Runnable::run), new MeshResidency<>(gpu, l));
    }
    private static final MeshResidency.Limits BIG = new MeshResidency.Limits(1000, 1L << 30, 1000, 1L << 30);

    private static Set<TileKey> keysOf(List<HorizonDrawPlanner.Draw<Integer>> d) { Set<TileKey> s = new HashSet<>(); for (var x : d) s.add(x.key()); return s; }

    // ---- VertexPacker / CameraRelative ----

    @Test void packedBuffersHaveTheDeclaredLayoutAndShading() {
        short[] top = new short[256], mat = new short[256];
        java.util.Arrays.fill(top, (short) 70); java.util.Arrays.fill(mat, (short) 1);
        LodTile t = new LodTile(new LodKey(0, 2, -1), top, mat, Confidence.OBSERVED);
        TerrainMesher.Mesh m = TerrainMesher.meshLocal(t, id -> "minecraft:stone", 4);
        ByteBuffer v = VertexPacker.packVertices(m), i = VertexPacker.packIndices(m);
        assertEquals(m.vertexCount() * 16, v.remaining());
        assertEquals(m.indices.length * 4, i.remaining());
        assertTrue(v.isDirect() && v.order() == java.nio.ByteOrder.nativeOrder());
        for (int k = 0; k < m.vertexCount(); k++) {
            int o = k * 16;
            assertTrue(v.getFloat(o) == m.positions[k * 3] && v.getFloat(o + 4) == m.positions[k * 3 + 1] && v.getFloat(o + 8) == m.positions[k * 3 + 2]);
            assertEquals(255, v.get(o + 15) & 0xFF, "opaque alpha");
        }
        int top0 = -1, side0 = -1;
        for (int k = 0; k < m.vertexCount(); k++) { if (m.faces[k] == TerrainMesher.Face.UP.ordinal()) top0 = k; if (m.faces[k] == TerrainMesher.Face.SKIRT.ordinal()) side0 = k; }
        assertTrue(top0 >= 0 && side0 >= 0);
        int topR = v.get(top0 * 16 + 12) & 0xFF, skirtR = v.get(side0 * 16 + 12) & 0xFF;
        assertEquals((m.colors[top0] >> 16) & 0xFF, topR, "top faces are not darkened");
        assertTrue(skirtR < topR, "skirt is darker than the top");
        for (int k = 0; k < m.indices.length; k++) assertEquals(m.indices[k], i.getInt(k * 4));
    }

    @Test void cameraRelativePlacementIsPreciseAtNegativeAndHugeCoordinates() {
        double[][] cases = {{0, 0}, {-1234.5, 99.25}, {-29_999_984, 29_999_984}, {29_999_999.5, -29_999_999.5}, {16_777_217, 16_777_219}};
        for (double[] c : cases) {
            double camX = c[0], camZ = c[1], camY = 311.7;
            // tile 100 blocks east/south of the camera, snapped to the 16-block tile grid
            long tx = Math.floorDiv((long) Math.floor(camX) + 96, 16), tz = Math.floorDiv((long) Math.floor(camZ) + 96, 16);
            double ox = tx * 16.0, oz = tz * 16.0;
            double[] p = CameraRelative.vertexRelativeToCamera(ox, oz, 7f, 64f, 3f, camX, camY, camZ);
            assertTrue(Math.abs(p[0] - (ox + 7 - camX)) < 1e-3, "x at " + camX);
            assertTrue(Math.abs(p[1] - (64 - camY)) < 1e-3, "y");
            assertTrue(Math.abs(p[2] - (oz + 3 - camZ)) < 1e-3, "z at " + camZ);
            assertTrue(Math.abs(p[0]) < 200 && Math.abs(p[2]) < 200, "offset small, so float math on the GPU is fine");
        }
        // the naive absolute-float way is NOT precise there: this is the failure being avoided
        float naive = (float) 29_999_999.5 - (float) 29_999_990.0;
        assertTrue(Math.abs(naive - 9.5f) > 0.4f);
    }

    // ---- MeshResidency / HorizonDrawPlanner ----

    @Test void drawsAreCameraRelativeAndCountedPerStage() {
        Store s = new Store(); FakeGpu gpu = new FakeGpu();
        s.put(0, -10, 4, 1, 64);                                  // x -160..-144, z 64..80
        try (HorizonDrawPlanner<Integer> p = planner(s, gpu, BIG)) {
            p.tick(-100, 70, 0, 2000, null);
            var draws = p.frame(-100, 80, 70);
            assertEquals(1, draws.size());
            var d = draws.get(0);
            assertEquals(-160.0 + 100.0, (double) d.offX());
            assertEquals(-80.0, (double) d.offY());
            assertEquals(64.0 - 70.0, (double) d.offZ());
            var g = p.diagnostics();
            assertEquals(1, g.tilesReceived()); assertEquals(1, g.meshesBuilt()); assertEquals(1, g.uploads());
            assertEquals(1, g.drawsLastFrame()); assertEquals(0, g.drawsSubmittedTotal(), "planned is not submitted");
            p.recordSubmitted(draws.size());
            assertEquals(1, p.diagnostics().drawsSubmittedTotal());
            // moving the camera changes the offset but does not re-upload
            var moved = p.frame(5000, 80, 70);
            assertEquals(1, gpu.uploads);
            assertEquals(-160.0 - 5000.0, (double) moved.get(0).offX());
        }
    }

    @Test void aNewRevisionIsUploadedBeforeTheOldHandleIsFreedAndAFailedUploadKeepsTheOldMesh() {
        Store s = new Store(); FakeGpu gpu = new FakeGpu();
        s.put(0, 10, 0, 1, 64);
        try (HorizonDrawPlanner<Integer> p = planner(s, gpu, BIG)) {
            p.tick(0, 0, 0, 5000, null); var a = p.frame(0, 80, 0);
            int first = a.get(0).handle();
            s.put(0, 10, 0, 2, 90);
            p.tick(0, 0, 0, 5000, null);                          // builds rev 2
            gpu.failUploads = true;
            var during = p.frame(0, 80, 0);
            assertEquals((Object) first, (Object) during.get(0).handle(), "failed upload: old mesh still drawn");
            assertEquals(1, during.get(0).revision());
            p.frame(0, 80, 0); p.frame(0, 80, 0);
            assertEquals(1, p.diagnostics().uploadFailures(), "a failed revision is not retried every frame");
            gpu.failUploads = false;
            s.put(0, 10, 0, 3, 91);
            p.tick(0, 0, 0, 5000, null);
            var ok = p.frame(0, 80, 0);
            assertEquals(3, ok.get(0).revision());
            assertTrue(!gpu.live.contains(first) && gpu.live.size() == 1, "old handle freed after the new one existed");
            assertEquals(2, gpu.uploads);
        }
    }

    @Test void dropOutOfRangeAndReleaseAllFreeEverythingExactlyOnce() {
        Store s = new Store(); FakeGpu gpu = new FakeGpu();
        s.put(0, 10, 0, 1, 64); s.put(0, 11, 0, 1, 64); s.put(0, 12, 0, 1, 64);
        HorizonDrawPlanner<Integer> p = planner(s, gpu, BIG);
        p.tick(0, 0, 0, 5000, null); assertEquals(3, p.frame(0, 80, 0).size());
        s.tiles.remove(key(0, 10, 0));                            // server DROP
        p.tick(0, 0, 0, 5000, null); assertEquals(2, p.frame(0, 80, 0).size());
        assertEquals(2, gpu.live.size());
        p.tick(0, 0, 0, 180, null);                               // camera limit shrinks: tiles at x>=176.. leave the candidates
        p.frame(0, 80, 0);
        assertTrue(gpu.live.size() < 2, "out-of-range tiles are freed");
        p.tick(0, 0, 0, 5000, null); p.frame(0, 80, 0);
        p.release();                                              // dimension change / disconnect
        assertEquals(0, gpu.live.size()); assertEquals(0, p.residency().size()); assertEquals(0, p.residency().bytes());
        assertEquals(0, p.frame(0, 80, 0).size() == 0 ? 0 : 1);
        p.release(); p.close();                                   // shutdown after release: idempotent, no double free (FakeGpu throws on one)
        assertEquals(0, gpu.live.size());
        assertEquals(gpu.uploads, gpu.frees, "every upload was freed exactly once");
    }

    @Test void residencyIsBoundedByCountAndBytesAndEvictsTheFarthest() {
        Store s = new Store(); FakeGpu gpu = new FakeGpu();
        for (int i = 10; i < 20; i++) s.put(0, i, 0, 1, 64);
        try (HorizonDrawPlanner<Integer> p = planner(s, gpu, new MeshResidency.Limits(4, 1L << 30, 100, 1L << 30))) {
            p.tick(0, 0, 0, 5000, null);
            var d = p.frame(0, 80, 0);
            assertEquals(4, p.residency().size());
            assertEquals(Set.of(key(0, 10, 0), key(0, 11, 0), key(0, 12, 0), key(0, 13, 0)), keysOf(d), "nearest tiles win");
            assertTrue(gpu.live.size() == 4);
        }
        Store s2 = new Store(); FakeGpu gpu2 = new FakeGpu();
        for (int i = 10; i < 20; i++) s2.put(0, i, 0, 1, 64);
        TerrainMesher.Mesh one = TerrainMesher.meshLocal(new LodTile(new LodKey(0, 10, 0), filled(64), filled(1), Confidence.OBSERVED), id -> "minecraft:stone", 4);
        long per = VertexPacker.vertexBytes(one) + VertexPacker.indexBytes(one);
        try (HorizonDrawPlanner<Integer> p = planner(s2, gpu2, new MeshResidency.Limits(1000, per * 3 + per / 2, 100, 1L << 30))) {
            p.tick(0, 0, 0, 5000, null); p.frame(0, 80, 0);
            assertEquals(3, p.residency().size());
            assertTrue(p.residency().bytes() <= per * 3 + per / 2);
        }
    }
    private static short[] filled(int v) { short[] a = new short[256]; java.util.Arrays.fill(a, (short) v); return a; }

    @Test void uploadsPerFrameAreBounded() {
        Store s = new Store(); FakeGpu gpu = new FakeGpu();
        for (int i = 10; i < 16; i++) s.put(0, i, 0, 1, 64);
        try (HorizonDrawPlanner<Integer> p = planner(s, gpu, new MeshResidency.Limits(1000, 1L << 30, 2, 1L << 30))) {
            p.tick(0, 0, 0, 5000, null);
            assertEquals(2, p.frame(0, 80, 0).size());
            assertEquals(4, p.frame(0, 80, 0).size());
            assertEquals(6, p.frame(0, 80, 0).size());
            assertEquals(6, p.frame(0, 80, 0).size());
            assertEquals(6, gpu.uploads);
        }
    }

    @Test void lodSwapNeverOpensAHoleOrDrawsTwoLayersEvenWhenGpuUploadsLagBehindTheCpuMeshes() {
        Store s = new Store(); FakeGpu gpu = new FakeGpu();
        s.put(1, 4, 0, 1, 70);                                    // coarse tile, x 128..160
        // upload at most ONE mesh per frame so children arrive on the GPU one by one
        try (HorizonDrawPlanner<Integer> p = planner(s, gpu, new MeshResidency.Limits(1000, 1L << 30, 1, 1L << 30))) {
            p.tick(0, 0, 0, 5000, null);
            assertEquals(Set.of(key(1, 4, 0)), keysOf(p.frame(0, 80, 0)));
            s.put(0, 8, 0, 1, 70); s.put(0, 9, 0, 1, 70); s.put(0, 8, 1, 1, 70); s.put(0, 9, 1, 1, 70);
            p.tick(0, 0, 0, 5000, null);                          // CPU meshes of all four children exist now
            List<Set<TileKey>> frames = new ArrayList<>();
            for (int f = 0; f < 6; f++) frames.add(keysOf(p.frame(0, 80, 0)));
            for (int f = 0; f < 6; f++) {
                Set<TileKey> d = frames.get(f);
                boolean parent = d.contains(key(1, 4, 0));
                int kids = 0;
                for (TileKey k : d) if (k.lod() == 0) kids++;
                assertTrue(parent || kids == 4, "frame " + f + ": covered by the parent or by all four children, never a hole: " + d);
                assertFalse(parent && kids > 0, "frame " + f + ": never parent and children together (double layer): " + d);
            }
            assertEquals(Set.of(key(0, 8, 0), key(0, 9, 0), key(0, 8, 1), key(0, 9, 1)), frames.get(5));
        }
    }

    @Test void aTileWithNoTrianglesCountsAsCoveredButIsNeverDrawn() {
        Store s = new Store(); FakeGpu gpu = new FakeGpu();
        short[] none = new short[256]; java.util.Arrays.fill(none, LodTile.NO_DATA);
        s.tiles.put(key(0, 10, 0), new ClientSession.Received(1, Provenance.values()[0], LodTileCodec.encode(new LodTile(new LodKey(0, 10, 0), none, new short[256], Confidence.OBSERVED))));
        s.put(0, 11, 0, 1, 64);
        try (HorizonDrawPlanner<Integer> p = planner(s, gpu, BIG)) {
            p.tick(0, 0, 0, 5000, null);
            assertEquals(Set.of(key(0, 11, 0)), keysOf(p.frame(0, 80, 0)));
            assertEquals(2, p.residency().size());
            assertEquals(1, gpu.uploads, "the empty mesh needs no GPU buffer");
        }
    }
}
