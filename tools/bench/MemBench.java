/** Heap cost of the server-side TerrainStore per observed chunk (SYNTHETIC smooth terrain: real terrain is rougher, so treat as a lower bound). Usage: tools/bench/run-membench.sh */
import dev.awre.stream.SyntheticTerrain;
import dev.horizonfield.core.*;
import dev.horizonfield.core.terrain.*;
public class MemBench {
    static long used() { for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(50); } catch (Exception e) { } } Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }
    public static void main(String[] a) {
        for (int side : new int[]{128, 256, 384}) {
            long before = used();
            TerrainStore st = new TerrainStore(6, TerrainStore.Reduce.MEAN, 20_000);
            int n = 0;
            for (int x = 0; x < side; x++) for (int z = 0; z < side; z++) { st.put(SyntheticTerrain.chunk(x, z, 5)); n++; }
            long after = used(); if (st.chunkCount() != n) throw new IllegalStateException();
            System.out.printf("TerrainStore: %,d chunks -> %.1f MiB heap = %.0f B/chunk (maxLod=6, versions map included)%n", n, (after - before) / 1048576.0, (double) (after - before) / n);
            System.out.printf("  store-reported chunk bytes: %.0f B/chunk (excludes version index)%n", (double) st.approxChunkBytes() / n);
            System.out.printf("  extrapolation: 1,000,000 chunks (16,384 x 16,384 blocks) = %.2f GiB%n", (double) (after - before) / n * 1e6 / (1L << 30));
            if (st.chunkCount() < 0) System.out.println();
        }
    }
}
