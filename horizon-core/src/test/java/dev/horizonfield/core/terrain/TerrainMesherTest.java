package dev.horizonfield.core.terrain;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.Confidence;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TerrainMesherTest {
    private static LodTile tile(int lod, int tx, int tz, java.util.function.IntBinaryOperator top, java.util.function.IntBinaryOperator mat) {
        short[] t = new short[256], m = new short[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) { int h = top.applyAsInt(x, z); t[z * 16 + x] = (short) h; m[z * 16 + x] = h == LodTile.NO_DATA ? 0 : (short) mat.applyAsInt(x, z); }
        return new LodTile(new LodKey(lod, tx, tz), t, m, Confidence.OBSERVED);
    }

    private static final java.util.function.IntFunction<String> PAL = id -> id == 1 ? "minecraft:grass_block" : id == 2 ? "minecraft:stone" : "minecraft:sand";

    private static void checkInvariants(LodTile t, TerrainMesher.Mesh m, double skirt) {
        final double cell = 1 << t.key().lod(), ox = t.key().tx() * 16 * cell, oz = t.key().tz() * 16 * cell;
        int valid = 0; double wall = 0; int boundary = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (t.topAt(x, z) == LodTile.NO_DATA) continue;
            valid++;
            int[][] nb = {{x, z - 1}, {x, z + 1}, {x - 1, z}, {x + 1, z}};
            for (int[] q : nb) {
                if (q[0] < 0 || q[1] < 0 || q[0] > 15 || q[1] > 15 || t.topAt(q[0], q[1]) == LodTile.NO_DATA) boundary++;
                else if (t.topAt(q[0], q[1]) < t.topAt(x, z)) wall += (t.topAt(x, z) - t.topAt(q[0], q[1])) * cell;
            }
        }
        double tol = 1e-3;
        assertTrue(Math.abs(m.area(TerrainMesher.Face.UP) - valid * cell * cell) < tol, "top area " + m.area(TerrainMesher.Face.UP) + " vs " + valid * cell * cell);
        double walls = 0;
        for (TerrainMesher.Face f : new TerrainMesher.Face[]{TerrainMesher.Face.NORTH, TerrainMesher.Face.SOUTH, TerrainMesher.Face.WEST, TerrainMesher.Face.EAST}) walls += m.area(f);
        assertTrue(Math.abs(walls - wall) < tol * Math.max(1, wall), "wall area " + walls + " vs " + wall);
        assertTrue(Math.abs(m.area(TerrainMesher.Face.SKIRT) - boundary * cell * skirt) < tol, "skirt area");
        assertEquals(m.indices.length / 3, m.triangleCount());
        for (int i : m.indices) assertTrue(i >= 0 && i < m.vertexCount());
        for (int v = 0; v < m.vertexCount(); v++) {
            assertTrue(m.positions[v * 3] >= ox - 1e-4 && m.positions[v * 3] <= ox + 16 * cell + 1e-4, "x in tile");
            assertTrue(m.positions[v * 3 + 2] >= oz - 1e-4 && m.positions[v * 3 + 2] <= oz + 16 * cell + 1e-4, "z in tile");
        }
        // winding: tops face up, walls face outwards
        for (int tri = 0; tri < m.indices.length; tri += 3) {
            double[] n = m.normal(tri);
            double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            assertTrue(len > 1e-9, "degenerate triangle");
            switch (TerrainMesher.Face.values()[m.faces[m.indices[tri]]]) {
                case UP -> assertTrue(n[1] / len > 0.999, "UP must face +y");
                case NORTH -> assertTrue(n[2] / len < -0.999, "NORTH faces -z");
                case SOUTH -> assertTrue(n[2] / len > 0.999, "SOUTH faces +z");
                case WEST -> assertTrue(n[0] / len < -0.999, "WEST faces -x");
                case EAST -> assertTrue(n[0] / len > 0.999, "EAST faces +x");
                case SKIRT -> assertTrue(Math.abs(n[1]) / len < 1e-6, "skirts are vertical");
            }
        }
    }

    @Test void flatTileIsOneQuadPlusFourSkirts() {
        LodTile t = tile(0, 3, -2, (x, z) -> 70, (x, z) -> 1);
        TerrainMesher.Mesh m = TerrainMesher.mesh(t, PAL, 4);
        assertEquals(5 * 2, m.triangleCount(), "1 merged top + 4 merged skirt strips");
        checkInvariants(t, m, 4);
    }

    @Test void randomTerrainSatisfiesAreaAndWindingInvariantsAtEveryLevel() {
        Random r = new Random(11);
        for (int lod = 0; lod <= 5; lod++) {
            for (int rep = 0; rep < 20; rep++) {
                int[] h = new int[256], mt = new int[256];
                for (int i = 0; i < 256; i++) { h[i] = r.nextInt(12) == 0 ? LodTile.NO_DATA : 60 + r.nextInt(5); mt[i] = 1 + r.nextInt(3); }
                LodTile t = tile(lod, r.nextInt(9) - 4, r.nextInt(9) - 4, (x, z) -> h[z * 16 + x], (x, z) -> mt[z * 16 + x]);
                checkInvariants(t, TerrainMesher.mesh(t, PAL, 2.0 * (1 << lod)), 2.0 * (1 << lod));
            }
        }
    }

    @Test void smoothSyntheticLikeTerrainMergesWellAndNoDataMakesNothing() {
        LodTile ramp = tile(2, 0, 0, (x, z) -> 60 + x / 4, (x, z) -> 1);          // 4 plateaus of width 4
        TerrainMesher.Mesh m = TerrainMesher.mesh(ramp, PAL, 8);
        int upTris = 0;
        for (int t = 0; t < m.indices.length; t += 3) if (m.faces[m.indices[t]] == TerrainMesher.Face.UP.ordinal()) upTris++;
        assertEquals(8, upTris, "4 plateaus = 4 merged quads = 8 triangles");
        assertTrue(Math.abs(m.area(TerrainMesher.Face.UP) - 256 * 16.0) < 1e-3);
        assertTrue(m.triangleCount() < 60, "greedy merge should keep this tiny, got " + m.triangleCount());
        LodTile empty = tile(0, 0, 0, (x, z) -> LodTile.NO_DATA, (x, z) -> 0);
        assertEquals(0, TerrainMesher.mesh(empty, PAL, 4).triangleCount());
    }

    @Test void deterministicAndColoursComeFromThePalette() {
        LodTile t = tile(0, 1, 1, (x, z) -> 64 + ((x * 7 + z * 3) % 3), (x, z) -> 1 + (x + z) % 3);
        TerrainMesher.Mesh a = TerrainMesher.mesh(t, PAL, 4), b = TerrainMesher.mesh(t, PAL, 4);
        assertArrayEquals(java.util.Arrays.toString(a.indices).getBytes(), java.util.Arrays.toString(b.indices).getBytes());
        assertEquals(a.vertexCount(), b.vertexCount());
        boolean sawGrass = false;
        for (int c : a.colors) if (c == BlockColors.argb("minecraft:grass_block")) sawGrass = true;
        assertTrue(sawGrass);
        assertEquals(0, BlockColors.argb("minecraft:air"));
        assertEquals(BlockColors.argb("mod:thing"), BlockColors.argb("mod:thing"));
        assertTrue((BlockColors.argb("mod:thing") >>> 24) == 0xFF);
    }

    @Test void meshLocalIsTheSameGeometryRelativeToTheTileOrigin() {
        for (int lod = 0; lod <= 3; lod++) {
            LodTile t = tile(lod, -3, 5, (x, z) -> 60 + ((x / 4 + z / 4) % 3) * 4, (x, z) -> 1 + (x + z) % 2);
            TerrainMesher.Mesh abs = TerrainMesher.mesh(t, PAL, 4), loc = TerrainMesher.meshLocal(t, PAL, 4);
            double cell = 1 << lod;
            assertEquals(0.0, abs.originX);
            assertEquals(-3.0 * 16 * cell, loc.originX);
            assertEquals(5.0 * 16 * cell, loc.originZ);
            assertEquals(abs.vertexCount(), loc.vertexCount());
            assertEquals(abs.triangleCount(), loc.triangleCount());
            for (int v = 0; v < abs.vertexCount(); v++) {
                assertTrue(Math.abs(abs.positions[v * 3] - (loc.positions[v * 3] + loc.originX)) < 1e-4, "x");
                assertEquals((Object) abs.positions[v * 3 + 1], (Object) loc.positions[v * 3 + 1], "y");
                assertTrue(Math.abs(abs.positions[v * 3 + 2] - (loc.positions[v * 3 + 2] + loc.originZ)) < 1e-4, "z");
                assertTrue(loc.positions[v * 3] >= 0 && loc.positions[v * 3] <= 16 * cell, "local x inside the tile");
                assertTrue(loc.positions[v * 3 + 2] >= 0 && loc.positions[v * 3 + 2] <= 16 * cell, "local z inside the tile");
            }
            assertEquals(java.util.Arrays.toString(abs.indices), java.util.Arrays.toString(loc.indices));
        }
    }

    @Test void meshLocalStaysExactAtTheWorldBorderWhereAbsoluteFloatsAreNot() {
        int tx = 1_874_999;                                     // x = 29,999,984 .. 30,000,000 at lod 0
        LodTile t = tile(0, tx, -tx, (x, z) -> 70, (x, z) -> 1);
        TerrainMesher.Mesh loc = TerrainMesher.meshLocal(t, PAL, 4);
        assertEquals((double) tx * 16, loc.originX);
        assertEquals(-(double) tx * 16, loc.originZ);
        double maxLocal = 0;
        for (int v = 0; v < loc.vertexCount(); v++) maxLocal = Math.max(maxLocal, Math.max(loc.positions[v * 3], loc.positions[v * 3 + 2]));
        assertEquals((Object) 16.0, (Object) maxLocal);          // exactly 16: nothing was rounded
        // the absolute mesh of the same tile is rounded to the float grid (2-block steps near 3e7): that is the bug meshLocal avoids
        TerrainMesher.Mesh abs = TerrainMesher.mesh(t, PAL, 4);
        double worst = 0;
        for (int v = 0; v < abs.vertexCount(); v++) worst = Math.max(worst, Math.abs(abs.positions[v * 3] - Math.rint(abs.positions[v * 3] / 2) * 2));
        assertTrue(worst < 1e-6, "absolute floats near 3e7 only land on multiples of 2");
    }

    /** Lowest y of the SKIRT vertices lying on the plane x = planeX (local coordinates) of a mesh. */
    private static double lowestSkirtOnPlaneX(TerrainMesher.Mesh m, float planeX) {
        double low = Double.MAX_VALUE;
        for (int v = 0; v < m.vertexCount(); v++)
            if (m.faces[v] == TerrainMesher.Face.SKIRT.ordinal() && m.positions[v * 3] == planeX) low = Math.min(low, m.positions[v * 3 + 1]);
        return low;
    }

    @Test void skirtToTheFloorClosesCracksThatAFixedDepthLeavesOpen() {
        LodTile high = tile(0, 0, 0, (x, z) -> 200, (x, z) -> 2);        // east border x=16, top 200 -> wall top y 201
        double lowerNeighbourSurface = 60 + 1;                             // neighbour tile top 60 -> surface y 61
        double fixed = lowestSkirtOnPlaneX(TerrainMesher.meshLocal(high, PAL, 4), 16f);
        assertTrue(fixed > lowerNeighbourSurface, "fixed 4-block skirt ends at " + fixed + ": a visible gap above y=61 (the bug being fixed)");
        double floor = lowestSkirtOnPlaneX(TerrainMesher.meshLocalToFloor(high, PAL, -64), 16f);
        assertTrue(floor <= -64 && floor <= lowerNeighbourSurface, "floor skirt ends at " + floor);
        // no extra triangles
        assertEquals(TerrainMesher.meshLocal(high, PAL, 4).triangleCount(), TerrainMesher.meshLocalToFloor(high, PAL, -64).triangleCount());
    }

    @Test void floorSkirtNeverHangsUpwardOnTilesLowerThanTheFloor() {
        LodTile deep = tile(0, 0, 0, (x, z) -> -100, (x, z) -> 2);         // top below the floor: skirt must still go DOWN from the top
        TerrainMesher.Mesh m = TerrainMesher.meshLocalToFloor(deep, PAL, -64);
        for (int t = 0; t < m.indices.length; t += 3) {
            double[] n = m.normal(t);
            assertTrue(Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]) > 0, "no degenerate triangles");
        }
        double top = -100 + 1;
        for (int v = 0; v < m.vertexCount(); v++) assertTrue(m.positions[v * 3 + 1] <= top + 1e-4, "nothing above the tile top");
    }
}
