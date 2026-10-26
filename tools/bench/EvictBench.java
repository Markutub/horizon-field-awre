import dev.awre.stream.SyntheticTerrain;
import dev.horizonfield.core.*;
import dev.horizonfield.core.terrain.*;

/** Cost of TerrainStore EVICT_OLDEST at a given cap: the O(n) scan runs once per batch of cap/64 admissions. Usage: tools/bench/run-evictbench.sh [cap] */
public class EvictBench {
    public static void main(String[] a) {
        int cap = a.length > 0 ? Integer.parseInt(a[0]) : 300_000;
        TerrainStore s = new TerrainStore(6, TerrainStore.Reduce.MEAN, 20_000);
        s.setFullPolicy(TerrainStore.FullPolicy.EVICT_OLDEST);
        s.setMaxChunks(cap);
        ColumnTile proto = SyntheticTerrain.chunk(0, 0, 1);
        long t = 0;
        int side = (int) Math.ceil(Math.sqrt(cap));
        long t0 = System.nanoTime();
        for (int i = 0; i < cap; i++) s.put(new ColumnTile(i % side, i / side, proto.topArray(), proto.materialArray(), Confidence.OBSERVED, ++t));
        double fillMs = (System.nanoTime() - t0) / 1e6;
        // now every admission is at the cap: measure the amortised cost per admission and the worst single admission
        int n = cap / 2; double worst = 0; t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long p0 = System.nanoTime();
            s.put(new ColumnTile(1_000_000 + i % side, i / side, proto.topArray(), proto.materialArray(), Confidence.OBSERVED, ++t));
            worst = Math.max(worst, (System.nanoTime() - p0) / 1e6);
        }
        double totalMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("cap=%,d: fill %.0f ms (%.1f us/put); %,d admissions at the cap: %.0f ms total = %.1f us each amortised, worst single admission %.1f ms, evicted %,d, held %,d%n",
                cap, fillMs, fillMs * 1000 / cap, n, totalMs, totalMs * 1000 / n, worst, s.evictedChunks(), s.chunkCount());
    }
}
