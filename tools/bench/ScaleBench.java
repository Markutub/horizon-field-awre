import dev.awre.protocol.*;
import dev.awre.stream.*;
import dev.horizonfield.core.lod.*;
import dev.horizonfield.core.terrain.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SYNTHETIC scale baseline (no thresholds): for N loaded chunks, measures store ingest rate and heap, then streams the plan through real
 * ServerSession/PlayerStreamer/ClientSession over an in-memory wire with a fake clock and reports tiles, wire bytes, CPU time per tile and
 * client-store size. Synthetic terrain is smoother than real terrain, so memory/bytes are LOWER BOUNDS. Usage: tools/bench/run-scalebench.sh
 */
public class ScaleBench {
    static long used() { for (int i = 0; i < 4; i++) { System.gc(); try { Thread.sleep(50); } catch (Exception e) { } } Runtime r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory(); }

    public static void main(String[] a) {
        System.out.println("N chunks | ingest ms (chunks/s) | store MiB (B/chunk) | leaves | tiles sent | wire MiB | stream CPU ms (us/tile) | client tiles | holes | strikes");
        for (int side : new int[]{32, 64, 128}) {                         // 1,024 / 4,096 / 16,384 chunks
            LodModel model = LodModel.defaults();
            long before = used();
            TerrainStore store = new TerrainStore(model.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
            long t0 = System.nanoTime();
            int lo = -side / 2, hi = side / 2 - 1;
            int n = SyntheticTerrain.fill(store, lo, lo, hi, hi, 1234);
            double ingestMs = (System.nanoTime() - t0) / 1e6;
            long storeBytes = used() - before;

            Limits lim = Limits.defaults().withStreaming(16, 64, 500).withClientTileCache(100_000);
            AtomicLong clock = new AtomicLong(1_000_000_000L);
            Counters sc = new Counters(), cc = new Counters();
            ServerSession server = new ServerSession(lim, clock::get, sc, 77);
            ClientSession client = new ClientSession(lim, cc, "bench", Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX);
            client.setPayloadValidator(TerrainPayloads.TERRAIN);
            Wire w = new Wire(server, client);
            w.toServer(client.start());
            double radius = Math.min(16000, side * 8.0);
            StreamConfig cfg = new StreamConfig(radius, 5_000_000, 1 << 20, 0.1, 50_000, 16, 256, 3);
            PlayerStreamer s = new PlayerStreamer(server, store, SyntheticTerrain.palette(), new LodPlanner(model), cfg, 1, 2, "minecraft:overworld", clock::get);
            long cpu = 0; int ticks = 0;
            do {
                clock.addAndGet(50_000_000L);
                long c0 = System.nanoTime();
                var frames = s.tick(0, 0, 0);
                cpu += System.nanoTime() - c0;
                for (byte[] f : frames) w.toClient(f);
            } while (++ticks < 20_000 && !(s.stats().holes() == 0 && s.stats().pending() == 0 && s.stats().held() > 0));
            var st = s.stats();
            System.out.printf("%,d | %.0f (%,.0f) | %.1f (%.0f) | %,d | %,d | %.2f | %.0f (%.0f) | %,d | %d | %d  [ticks=%d]%n",
                    n, ingestMs, n / (ingestMs / 1000), storeBytes / 1048576.0, (double) storeBytes / n, st.plannedLeaves(), st.sent(),
                    st.wireBytes() / 1048576.0, cpu / 1e6, st.sent() == 0 ? 0 : cpu / 1e3 / st.sent(), client.tileCount(), st.holes(), client.strikes(), ticks);
            if (store.chunkCount() < 0) System.out.println();
        }
    }

    record Wire(ServerSession server, ClientSession client) {
        void toServer(byte[] f) { for (byte[] r : server.onFrame(f)) toClient(r); }
        void toClient(byte[] f) { for (byte[] r : client.onFrame(f)) toServer(r); }
    }
}
