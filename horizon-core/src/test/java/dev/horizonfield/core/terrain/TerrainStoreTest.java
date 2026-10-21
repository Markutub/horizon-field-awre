package dev.horizonfield.core.terrain;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.lod.LodModel;
import dev.horizonfield.core.lod.LodPlanner;
import dev.horizonfield.core.stream.PyramidTileSource;
import dev.horizonfield.core.stream.StreamScheduler;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TerrainStoreTest {
    private final Random rnd = new Random(42);

    private ColumnTile chunk(int cx, int cz, Confidence c) {
        short[] top = new short[256], mat = new short[256];
        for (int i = 0; i < 256; i++) { top[i] = (short) (rnd.nextInt(200) - 30); mat[i] = (short) rnd.nextInt(5); }
        return new ColumnTile(cx, cz, top, mat, c, 1000);
    }

    // ---- independent reference: global-sample addressing, recursion straight from the definition ----
    private static int[] refSample(Map<Long, ColumnTile> world, int level, int gx, int gz, boolean max) {
        if (level == 0) {
            ColumnTile c = world.get(((long) Math.floorDiv(gx, 16) << 32) | (Math.floorDiv(gz, 16) & 0xFFFFFFFFL));
            if (c == null) return null;
            return new int[]{c.topAt(Math.floorMod(gx, 16), Math.floorMod(gz, 16)), c.materialAt(Math.floorMod(gx, 16), Math.floorMod(gz, 16))};
        }
        int n = 0, sum = 0, mx = Integer.MIN_VALUE;
        Map<Integer, Integer> counts = new HashMap<>();
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            int[] s = refSample(world, level - 1, gx * 2 + dx, gz * 2 + dz, max);
            if (s == null) continue;
            n++; sum += s[0]; mx = Math.max(mx, s[0]); counts.merge(s[1], 1, Integer::sum);
        }
        if (n == 0) return null;
        int best = 0, bc = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) if (e.getValue() > bc || (e.getValue() == bc && e.getKey() < best)) { bc = e.getValue(); best = e.getKey(); }
        return new int[]{max ? mx : Math.floorDiv(2 * sum + n, 2 * n), best};
    }

    private void fill(TerrainStore s, Map<Long, ColumnTile> world, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (rnd.nextInt(10) == 0) continue;                 // ~10% of chunks never observed
            ColumnTile c = chunk(x, z, rnd.nextBoolean() ? Confidence.OBSERVED : Confidence.PREDICTED);
            s.put(c); world.put(((long) x << 32) | (z & 0xFFFFFFFFL), c);
        }
    }

    @Test void everyLevelMatchesTheDefinitionIncludingNegativeCoordsAndHoles() {
        for (TerrainStore.Reduce r : TerrainStore.Reduce.values()) {
            TerrainStore s = new TerrainStore(4, r, 4096);
            Map<Long, ColumnTile> world = new HashMap<>();
            fill(s, world, -9, -9, 8, 8);
            for (int level = 1; level <= 4; level++) {
                int span = 16 >> level;                                  // tiles per axis covering chunks -16..15
                for (int tx = Math.floorDiv(-9, 1 << level); tx <= Math.floorDiv(8, 1 << level); tx++) {
                    for (int tz = Math.floorDiv(-9, 1 << level); tz <= Math.floorDiv(8, 1 << level); tz++) {
                        LodKey k = new LodKey(level, tx, tz);
                        LodTile t = s.tile(k);
                        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            int[] exp = refSample(world, level, tx * 16 + x, tz * 16 + z, r == TerrainStore.Reduce.MAX);
                            if (t == null) { assertNull(exp, "tile " + k + " missing but reference has data"); continue; }
                            if (exp == null) assertEquals(LodTile.NO_DATA, t.topAt(x, z), k + " sample " + x + "," + z);
                            else {
                                assertEquals(exp[0], t.topAt(x, z), r + " " + k + " top " + x + "," + z);
                                assertEquals(exp[1], t.materialAt(x, z), r + " " + k + " material " + x + "," + z);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test void level0IsTheChunkItself() {
        TerrainStore s = new TerrainStore(3, TerrainStore.Reduce.MEAN, 64);
        ColumnTile c = chunk(5, -7, Confidence.OBSERVED);
        s.put(c);
        LodTile t = s.tile(new LodKey(0, 5, -7));
        for (int i = 0; i < 256; i++) { assertEquals(c.topAt(i % 16, i / 16), t.topAt(i % 16, i / 16)); }
        assertEquals(Confidence.OBSERVED, t.confidence());
        assertNull(s.tile(new LodKey(0, 6, -7)));
        assertEquals(0L, s.version(new LodKey(0, 6, -7)));
    }

    @Test void versionsAreMonotoneStampAncestorsAndIgnoreIdenticalContent() {
        TerrainStore s = new TerrainStore(3, TerrainStore.Reduce.MEAN, 64);
        ColumnTile a = chunk(0, 0, Confidence.OBSERVED), b = chunk(1, 0, Confidence.OBSERVED);
        long va = s.put(a), vb = s.put(b);
        assertTrue(vb > va);
        assertEquals(va, s.put(a), "identical content must not bump");
        assertEquals(vb, s.version(new LodKey(1, 0, 0)));
        assertEquals(vb, s.version(new LodKey(3, 0, 0)));
        assertEquals(va, s.version(new LodKey(0, 0, 0)));
        assertEquals(0L, s.version(new LodKey(4, 0, 0)), "above maxLod");
        long vc = s.put(chunk(0, 0, Confidence.OBSERVED));
        assertTrue(vc > vb);
        assertEquals(vc, s.version(new LodKey(2, 0, 0)));
        assertEquals(vb, s.version(new LodKey(0, 1, 0)), "sibling untouched");
    }

    @Test void cachedAncestorsAreRebuiltAfterAChunkChanges() {
        TerrainStore s = new TerrainStore(2, TerrainStore.Reduce.MEAN, 64);
        s.put(chunk(0, 0, Confidence.OBSERVED));
        LodKey k = new LodKey(2, 0, 0);
        LodTile before = s.tile(k);
        short[] top = new short[256]; java.util.Arrays.fill(top, (short) 250);
        s.put(new ColumnTile(0, 0, top, new short[256], Confidence.OBSERVED, 1));
        LodTile after = s.tile(k);
        assertFalse(before.sameContent(after));
        assertEquals(250, after.topAt(0, 0));
    }

    @Test void confidenceIsTheWeakestContributor() {
        TerrainStore s = new TerrainStore(1, TerrainStore.Reduce.MEAN, 64);
        s.put(chunk(0, 0, Confidence.OBSERVED));
        assertEquals(Confidence.OBSERVED, s.tile(new LodKey(1, 0, 0)).confidence());
        s.put(chunk(1, 0, Confidence.PREDICTED));
        assertEquals(Confidence.PREDICTED, s.tile(new LodKey(1, 0, 0)).confidence());
    }

    @Test void materialTiesGoToTheLowestId() {
        TerrainStore s = new TerrainStore(1, TerrainStore.Reduce.MEAN, 64);
        short[] top = new short[256], mat = new short[256];
        mat[0] = 7; mat[1] = 3; mat[16] = 9; mat[17] = 3;   // 2x2 block at (0,0): 7,3,9,3 -> mode 3
        s.put(new ColumnTile(0, 0, top, mat, Confidence.OBSERVED, 1));
        assertEquals(3, s.tile(new LodKey(1, 0, 0)).materialAt(0, 0));
        mat[17] = 9;                                         // 7,3,9,9 -> 9
        s.put(new ColumnTile(0, 0, top, mat, Confidence.OBSERVED, 1));
        assertEquals(9, s.tile(new LodKey(1, 0, 0)).materialAt(0, 0));
        mat[16] = 4;                                         // 7,3,4,9 -> all tie -> lowest = 3
        s.put(new ColumnTile(0, 0, top, mat, Confidence.OBSERVED, 1));
        assertEquals(3, s.tile(new LodKey(1, 0, 0)).materialAt(0, 0));
    }

    @Test void codecRoundTripsAndRejectsMalformedPayloads() throws IOException {
        TerrainStore s = new TerrainStore(2, TerrainStore.Reduce.MEAN, 64);
        s.put(chunk(0, 0, Confidence.PREDICTED)); s.put(chunk(1, 1, Confidence.OBSERVED));
        for (LodKey k : new LodKey[]{new LodKey(0, 0, 0), new LodKey(1, 0, 0), new LodKey(2, 0, 0)}) {
            LodTile t = s.tile(k);
            byte[] b = LodTileCodec.encode(t);
            assertEquals(LodTileCodec.BYTES, b.length);
            assertTrue(t.sameContent(LodTileCodec.decode(k, b)), k.toString());
        }
        byte[] good = LodTileCodec.encode(s.tile(new LodKey(0, 0, 0)));
        assertThrows(IOException.class, () -> LodTileCodec.decode(new LodKey(0, 0, 0), new byte[10]));
        byte[] badFmt = good.clone(); badFmt[0] = 3;
        assertThrows(IOException.class, () -> LodTileCodec.decode(new LodKey(0, 0, 0), badFmt));
        byte[] badConf = good.clone(); badConf[1] = 9;
        assertThrows(IOException.class, () -> LodTileCodec.decode(new LodKey(0, 0, 0), badConf));
        byte[] noDataMat = good.clone();
        noDataMat[2] = (byte) 0x80; noDataMat[3] = 0;        // top[0] = NO_DATA
        noDataMat[2 + 512 + 1] = 5;                          // but material[0] = 5
        assertThrows(IOException.class, () -> LodTileCodec.decode(new LodKey(0, 0, 0), noDataMat));
    }

    @Test void schedulerSkipsAreasWithNoData() {
        TerrainStore s = new TerrainStore(6, TerrainStore.Reduce.MEAN, 4096);
        LodModel m = LodModel.defaults();
        PyramidTileSource src = new PyramidTileSource(s, m.maxLod(), 700);
        var plan = new LodPlanner(m).plan(0, 0, 1000, null);
        StreamScheduler sch = new StreamScheduler(m);
        StreamScheduler.Decision d = sch.decide(plan, 0, 0, 0, Long.MAX_VALUE / 4, 100000, src);
        assertTrue(d.send().isEmpty());
        assertEquals(plan.leaves().size(), d.noData());
        s.put(chunk(0, 0, Confidence.OBSERVED));
        d = sch.decide(plan, 0, 0, 0, Long.MAX_VALUE / 4, 100000, src);
        assertEquals(1, d.send().size(), "only the one leaf that now has data");
    }
}
