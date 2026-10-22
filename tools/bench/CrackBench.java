import dev.awre.stream.SyntheticTerrain;
import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.TerrainStore;

/**
 * Offline measurement of see-through cracks at tile borders (SYNTHETIC terrain only; no real capture was available).
 * A tile skirt hangs `depth` blocks below its border top. Between two neighbouring samples with tops hHi > hLo the wall of the
 * higher tile is hidden only when hHi - hLo <= depth; otherwise (hHi - hLo - depth) blocks of the neighbour's side stay open.
 * Usage: java -cp <classes> CrackBench   (classes = horizon-core + awre-protocol + awre-stream main)
 */
public class CrackBench {
    interface Depth { double of(int lod); }

    static int rough(int x, int z, double amp) {
        double s = SyntheticTerrain.heightAt(x, z, 7L) - 64;
        double ridge = 38 * Math.abs(StrictMath.sin(x / 41.0 + StrictMath.sin(z / 67.0))) * StrictMath.cos(z / 53.0);
        return (int) Math.max(1, Math.min(300, Math.round(64 + s * amp + ridge * amp)));
    }

    public static void main(String[] a) {
        final int CH = 128;                                    // 128x128 chunks = 2048 x 2048 blocks
        for (double amp : new double[]{1.0, 3.0}) {
            TerrainStore st = new TerrainStore(4, TerrainStore.Reduce.MEAN, 100000);
            for (int cx = 0; cx < CH; cx++) for (int cz = 0; cz < CH; cz++) {
                short[] top = new short[256], mat = new short[256];
                for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) { top[lz * 16 + lx] = (short) rough(cx * 16 + lx, cz * 16 + lz, amp); mat[lz * 16 + lx] = 1; }
                st.put(new ColumnTile(cx, cz, top, mat, Confidence.PREDICTED, 0));
            }
            System.out.printf("%n== terrain amplitude x%.0f (synthetic)%n", amp);
            Depth[] policies = {lod -> Math.max(4, 2 << lod), lod -> 16 + (8 << lod), lod -> 320};
            String[] names = {"current max(4,2<<lod)", "16+(8<<lod)", "constant 320 (to the floor)"};
            for (int lod = 0; lod <= 4; lod++) {
                int n = (CH * 16) / (16 << lod);
                StringBuilder row = new StringBuilder();
                double maxDiff = 0; long borders = 0;
                double[] exposed = new double[policies.length]; long[] crackCells = new long[policies.length];
                for (int tx = 0; tx < n; tx++) for (int tz = 0; tz < n; tz++) {
                    LodTile t = st.tile(new LodKey(lod, tx, tz));
                    if (t == null) continue;
                    for (int d = 0; d < 2; d++) {                                  // east and south neighbours, same lod
                        LodTile u = st.tile(new LodKey(lod, d == 0 ? tx + 1 : tx, d == 1 ? tz + 1 : tz));
                        if (u == null) continue;
                        for (int i = 0; i < 16; i++) {
                            int h1 = d == 0 ? t.topAt(15, i) : t.topAt(i, 15), h2 = d == 0 ? u.topAt(0, i) : u.topAt(i, 0);
                            int diff = Math.abs(h1 - h2); borders++; maxDiff = Math.max(maxDiff, diff);
                            for (int p = 0; p < policies.length; p++) {
                                double over = diff - policies[p].of(lod);
                                if (over > 0) { exposed[p] += over * (1 << lod); crackCells[p]++; }
                            }
                        }
                    }
                }
                row.append(String.format("lod%d border samples=%d max step=%.0f |", lod, borders, maxDiff));
                for (int p = 0; p < policies.length; p++) row.append(String.format(" %s: %d cracks, %.0f block^2 |", names[p], crackCells[p], exposed[p]));
                System.out.println(row);
            }
        }
    }
}
