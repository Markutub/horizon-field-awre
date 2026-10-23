package dev.horizonfield.core.terrain;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import java.util.Random;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 geometry oracle. {@link TerrainMesherTest} checks AREAS (top, wall, skirt) and winding; an area sum cannot notice a wall that
 * is the right size but sits at the wrong height or plane. This test samples the mesh by ray and compares every sample with what the
 * height field says the surface must be:
 *  - every valid cell has exactly ONE top quad covering its interior, at y = top + 1; a no-data cell has none;
 *  - for every vertical edge plane, every sample inside the expected span [lowY, highY) is covered by exactly ONE wall/skirt triangle
 *    (no gap, no double wall = no z-fighting), tagged with the expected face, and nothing is covered above highY or below lowY;
 *  - two tiles of different LOD meshed independently (floor skirts) leave no uncovered point on their shared plane.
 * Sample offsets are deliberately irrational-looking so no sample lies on a quad diagonal.
 */
class TerrainGeometryOracleTest {
    private static final java.util.function.IntFunction<String> PAL = id -> id == 1 ? "minecraft:grass_block" : id == 2 ? "minecraft:stone" : "minecraft:sand";
    private static final double FX = 0.3137, FY = 0.4113, EPS = 1e-4, FLOOR = -64;
    private static final TerrainMesher.Face[] WALL_FACE = {TerrainMesher.Face.NORTH, TerrainMesher.Face.SOUTH, TerrainMesher.Face.WEST, TerrainMesher.Face.EAST};

    static LodTile tile(int lod, int tx, int tz, IntBinaryOperator top) {
        short[] t = new short[256], m = new short[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) { int h = top.applyAsInt(x, z); t[z * 16 + x] = (short) h; m[z * 16 + x] = (short) (h == LodTile.NO_DATA ? 0 : 1 + ((x / 4 + z / 4) & 1)); }
        return new LodTile(new LodKey(lod, tx, tz), t, m, Confidence.OBSERVED);
    }

    // ---- sampling ----
    private static boolean inTri(double px, double py, double ax, double ay, double bx, double by, double cx, double cy) {
        double d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by), d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy), d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        boolean neg = d1 < -1e-12 || d2 < -1e-12 || d3 < -1e-12, pos = d1 > 1e-12 || d2 > 1e-12 || d3 > 1e-12;
        return !(neg && pos);
    }

    /** World position of vertex v: local meshes add their origin. */
    private static double[] w(TerrainMesher.Mesh m, int v) { return new double[]{m.positions[v * 3] + m.originX, m.positions[v * 3 + 1], m.positions[v * 3 + 2] + m.originZ}; }

    /** Number of UP triangles covering (x,z), and the y of the last one. */
    private static int[] up(TerrainMesher.Mesh m, double x, double z, double[] yOut) {
        int n = 0;
        for (int t = 0; t < m.indices.length; t += 3) {
            if (m.faces[m.indices[t]] != TerrainMesher.Face.UP.ordinal()) continue;
            double[] a = w(m, m.indices[t]), b = w(m, m.indices[t + 1]), c = w(m, m.indices[t + 2]);
            if (inTri(x, z, a[0], a[2], b[0], b[2], c[0], c[2])) { n++; yOut[0] = a[1]; }
        }
        return new int[]{n};
    }

    /** Count of non-UP triangles lying in the vertical plane (axis 'x' => x==plane, else z==plane) that contain (along, y); faces seen are OR-ed into mask. */
    private static int wall(TerrainMesher.Mesh m, boolean planeIsX, double plane, double along, double y, int[] mask) {
        int n = 0;
        for (int t = 0; t < m.indices.length; t += 3) {
            int f = m.faces[m.indices[t]];
            if (f == TerrainMesher.Face.UP.ordinal()) continue;
            double[] a = w(m, m.indices[t]), b = w(m, m.indices[t + 1]), c = w(m, m.indices[t + 2]);
            int k = planeIsX ? 0 : 2, o = planeIsX ? 2 : 0;
            if (Math.abs(a[k] - plane) > EPS || Math.abs(b[k] - plane) > EPS || Math.abs(c[k] - plane) > EPS) continue;
            if (inTri(along, y, a[o], a[1], b[o], b[1], c[o], c[1])) { n++; mask[0] |= 1 << f; }
        }
        return n;
    }

    /** Full positional check of one tile mesh against its own height field. {@code floor}: skirt bottom (absolute y) or NaN for fixed depth {@code depth}. */
    static void check(LodTile t, TerrainMesher.Mesh m, double floor, double depth) {
        final int n = LodTile.SIDE; final double cell = 1 << t.key().lod();
        final double ox = (double) t.key().tx() * n * cell, oz = (double) t.key().tz() * n * cell;
        String id = " [lod " + t.key().lod() + " tx " + t.key().tx() + " tz " + t.key().tz() + "]";
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            int h = t.topAt(x, z); boolean valid = h != LodTile.NO_DATA;
            double[] y = new double[1];
            int hits = up(m, ox + (x + FX) * cell, oz + (z + (1 - FX)) * cell, y)[0];
            if (!valid) { assertEquals(0, hits, "no-data cell " + x + "," + z + " must have no top" + id); continue; }
            assertEquals(1, hits, "cell " + x + "," + z + " must be covered by exactly one top" + id);
            assertTrue(Math.abs(h + 1 - y[0]) < EPS, "top height of cell " + x + "," + z + " is " + y[0] + ", expected " + (h + 1) + id);
            for (int dir = 0; dir < 4; dir++) {
                int nx = x + (dir == 2 ? -1 : dir == 3 ? 1 : 0), nz = z + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                boolean out = nx < 0 || nz < 0 || nx >= n || nz >= n || t.topAt(nx, nz) == LodTile.NO_DATA;
                if (!out && t.topAt(nx, nz) >= h) continue;                       // the lower/equal side owns no wall; the higher side emits it
                double hi = h + 1, lo = out ? (Double.isNaN(floor) ? hi - depth : Math.min(floor, hi - 1.0)) : t.topAt(nx, nz) + 1;
                boolean planeX = dir >= 2;
                double plane = planeX ? ox + (dir == 2 ? x : x + 1) * cell : oz + (dir == 0 ? z : z + 1) * cell;
                double along = planeX ? oz + (z + FX) * cell : ox + (x + FX) * cell;
                int[] mask = {0};
                for (double yy = lo; yy < hi - 1e-9; yy += 1.0) {                   // one sample per integer level inside the span
                    mask[0] = 0; int c = wall(m, planeX, plane, along, yy + FY, mask);
                    assertEquals(1, c, "dir " + dir + " cell " + x + "," + z + " level " + yy + " must be covered exactly once" + id);
                    int want = 1 << (out ? TerrainMesher.Face.SKIRT.ordinal() : WALL_FACE[dir].ordinal());
                    assertEquals(want, mask[0], "wrong face tag at dir " + dir + " cell " + x + "," + z + id);
                }
                mask[0] = 0;
                assertEquals(0, wall(m, planeX, plane, along, hi + FY, mask), "wall must not extend above the higher top, cell " + x + "," + z + id);
                assertEquals(0, wall(m, planeX, plane, along, lo - (1 - FY), mask), "wall must not extend below its span, cell " + x + "," + z + id);
            }
        }
    }

    // ---- fixtures ----
    private static void all(String name, IntBinaryOperator top) {
        int[][] where = {{0, 0, 0}, {0, -3, -5}, {2, -1, 4}, {5, -7, -2}};          // lod, tx, tz: includes negative tile coordinates and coarse LODs
        for (int[] w : where) {
            LodTile t = tile(w[0], w[1], w[2], top);
            check(t, TerrainMesher.mesh(t, PAL, 3.0), Double.NaN, 3.0);
            check(t, TerrainMesher.meshLocal(t, PAL, 3.0), Double.NaN, 3.0);
            check(t, TerrainMesher.meshLocalToFloor(t, PAL, FLOOR), FLOOR, 0);
        }
    }

    @Test void flat() { all("flat", (x, z) -> 70); }
    @Test void rampAlongX() { all("ramp", (x, z) -> 60 + x); }
    @Test void cliff() { all("cliff", (x, z) -> x < 8 ? 90 : 40); }
    @Test void ridge() { all("ridge", (x, z) -> 80 - 3 * Math.abs(x - 8)); }
    @Test void valleyWithNegativeHeights() { all("valley", (x, z) -> -60 + 2 * Math.abs(z - 8) - (x & 1)); }
    @Test void checkerboardIsTheWorstCaseForWallMerging() { all("checker", (x, z) -> 64 + ((x + z) & 1) * 5); }
    @Test void holesAndNoDataStripes() { all("holes", (x, z) -> (x == 5 || (z >= 3 && z <= 4 && x > 9) || (x == 0 && z == 0)) ? LodTile.NO_DATA : 62 + (x ^ z) % 3); }
    @Test void singlePillarAndSinglePit() { all("pillar", (x, z) -> x == 7 && z == 7 ? 120 : x == 3 && z == 12 ? 20 : 64); }

    @Test void randomTerrainAtEveryLevel() {
        Random r = new Random(2026);
        for (int lod = 0; lod <= 5; lod++) for (int rep = 0; rep < 12; rep++) {
            int[] h = new int[256]; for (int i = 0; i < 256; i++) h[i] = r.nextInt(10) == 0 ? LodTile.NO_DATA : -63 + r.nextInt(80);
            LodTile t = tile(lod, r.nextInt(21) - 10, r.nextInt(21) - 10, (x, z) -> h[z * 16 + x]);
            check(t, TerrainMesher.meshLocalToFloor(t, PAL, FLOOR), FLOOR, 0);
        }
    }

    // ---- two tiles of different LOD, meshed independently ----

    /** Heights of the cell of tile t whose extent contains world coordinate (wx, wz); NO_DATA when outside. */
    private static int heightAt(LodTile t, double wx, double wz) {
        double cell = 1 << t.key().lod(), n = LodTile.SIDE;
        int cx = (int) Math.floor((wx - t.key().tx() * n * cell) / cell), cz = (int) Math.floor((wz - t.key().tz() * n * cell) / cell);
        return cx < 0 || cz < 0 || cx >= n || cz >= n ? LodTile.NO_DATA : t.topAt(cx, cz);
    }

    private static void seam(LodTile a, LodTile b, boolean planeIsX, double plane, double alongFrom, double alongTo) {
        TerrainMesher.Mesh ma = TerrainMesher.meshLocalToFloor(a, PAL, FLOOR), mb = TerrainMesher.meshLocalToFloor(b, PAL, FLOOR);
        int samples = 0;
        for (double s = alongFrom + 0.21; s < alongTo; s += 0.5) {                    // finer than the finest cell on the seam
            double ax = planeIsX ? plane - 0.01 : s, az = planeIsX ? s : plane - 0.01, bx = planeIsX ? plane + 0.01 : s, bz = planeIsX ? s : plane + 0.01;
            int ha = heightAt(a, ax, az), hb = heightAt(b, bx, bz);
            if (ha == LodTile.NO_DATA || hb == LodTile.NO_DATA) continue;
            int[] mk = {0};
            double top = Math.max(ha, hb) + 1;
            for (double y = FLOOR + 0.37; y < top; y += 1.0) {
                int c = wall(ma, planeIsX, plane, s, y, mk) + wall(mb, planeIsX, plane, s, y, mk);
                assertTrue(c >= 1, "crack on the seam at along=" + s + " y=" + y + " (heights " + ha + " | " + hb + ")");
                if (y >= Math.min(ha, hb) + 1) assertEquals(1, c, "the visible cliff between the tiles is drawn once at along=" + s + " y=" + y);
            }
            assertEquals(0, wall(ma, planeIsX, plane, s, top + 0.37, mk) + wall(mb, planeIsX, plane, s, top + 0.37, mk), "nothing above the higher side");
            samples++;
        }
        assertTrue(samples > 20, "the seam fixture must actually exercise the border: " + samples);
    }

    @Test void adjacentTilesOfDifferentLodLeaveNoCrackAndNoDoubleCliff() {
        IntBinaryOperator hill = (x, z) -> 50 + (int) (40 * Math.sin(x * 0.7) * Math.cos(z * 0.5)) + 2 * x;   // steep and uneven on both sides of the seam
        // x-neighbours: A = LOD0 at tx=-1 (x -16..0), B = LOD1 at tx=0 (x 0..32). z range of A: 0..16
        seam(tile(0, -1, 0, hill), tile(1, 0, 0, (x, z) -> 70 - 4 * x + (z & 3) * 6), true, 0, 0, 16);
        // z-neighbours at LOD 1 | 3 in negative space: A at lod1 tz=-1 (z -32..0), B lod3 tz=0 (z 0..128); A x range: tx=-2 -> -64..-32
        seam(tile(1, -2, -1, hill), tile(3, -1, 0, (x, z) -> 30 + 5 * z), false, 0, -64, -32);
        // 3-level jump: A = LOD0 tx=7 (x 112..128), B = LOD3 tx=1 (x 128..256, cell 8); a LOD3 tile origin is a multiple of 128
        seam(tile(0, 7, 0, (x, z) -> 100 - 6 * z), tile(3, 1, 0, (x, z) -> 40 + x), true, 128, 0, 16);
    }
}
