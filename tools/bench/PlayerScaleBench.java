import dev.awre.protocol.*;
import dev.awre.stream.*;
import dev.horizonfield.core.lod.*;
import dev.horizonfield.core.terrain.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SYNTHETIC N-player streaming cost (REVIEW_HONEST item 9 / NEXT_PHASE_PROMPT task 2.3). One shared TerrainStore, N independent
 * ServerSession + PlayerStreamer + ClientSession triples over in-memory wires with a fake clock (100 ms per step = the plugin's
 * 2-tick timer). Measures, per N:
 *   (a) join: total tick() CPU until every player has a complete horizon, per player;
 *   (b) steady: players walk 0.6 blocks/step (about 5.6 blocks/s); tick() cost per call (p50/p99/max) and the CPU seconds consumed
 *       per simulated second by all players together (= cores the streaming alone would need if run flat out).
 * NOT modelled: lock contention between concurrent jobs, GC pressure from a real server heap, real worldgen terrain (synthetic
 * terrain is smoother, so byte and tile counts are LOWER BOUNDS), cold JIT on a fresh server. Single run; use as an order of magnitude.
 * Usage: bash tools/bench/run-playerscalebench.sh
 */
public class PlayerScaleBench {
    static final long STEP_NS = 100_000_000L;

    public static void main(String[] a) {
        int side = 64;                                                    // 4,096 chunks, the HANDOFF acceptance scale
        LodModel model = LodModel.defaults();
        TerrainStore store = new TerrainStore(model.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
        int lo = -side / 2, hi = side / 2 - 1;
        int chunks = SyntheticTerrain.fill(store, lo, lo, hi, hi, 1234);
        System.out.printf("# store: %,d chunks (side %d), JVM %s, cpus=%d%n", chunks, side, System.getProperty("java.version"), Runtime.getRuntime().availableProcessors());
        System.out.println("N | join CPU ms total (per player) | join ticks | steady tick us p50/p99/max | steady CPU ms per sim-second (all players) | holes | strikes");
        // warm the JIT with a throwaway run so the first row is not dominated by interpreter time
        run(store, model, 4, 100, false);
        for (int n : new int[]{1, 4, 16, 64}) run(store, model, n, 200, true);
    }

    static void run(TerrainStore store, LodModel model, int n, int steadySteps, boolean print) {
        Limits lim = Limits.defaults().withStreaming(16, 64, 500).withClientTileCache(100_000);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        PlayerStreamer[] st = new PlayerStreamer[n];
        Wire[] wires = new Wire[n];
        ClientSession[] clients = new ClientSession[n];
        double[] px = new double[n], pz = new double[n];
        for (int i = 0; i < n; i++) {
            Counters sc = new Counters(), cc = new Counters();
            ServerSession server = new ServerSession(lim, clock::get, sc, 77 + i);
            ClientSession client = new ClientSession(lim, cc, "bench" + i, Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX);
            client.setPayloadValidator(TerrainPayloads.TERRAIN);
            Wire w = new Wire(server, client);
            w.toServer(client.start());
            StreamConfig cfg = new StreamConfig(512, 5_000_000, 1 << 20, 0.1, 50_000, 16, 256, 3);
            st[i] = new PlayerStreamer(server, store, SyntheticTerrain.palette(), new LodPlanner(model), cfg, 1, 2, "minecraft:overworld", clock::get);
            wires[i] = w; clients[i] = client;
            px[i] = ((i % 8) - 3.5) * 60; pz[i] = ((i / 8) % 8 - 3.5) * 60;   // spread inside the store
        }
        // (a) join: tick everyone until all have a complete horizon
        long joinCpu = 0; int ticks = 0; boolean[] done = new boolean[n];
        int remaining = n;
        while (remaining > 0 && ticks < 20_000) {
            clock.addAndGet(STEP_NS);
            for (int i = 0; i < n; i++) {
                if (done[i]) continue;
                long c0 = System.nanoTime();
                var frames = st[i].tick(px[i], 0, pz[i]);
                joinCpu += System.nanoTime() - c0;
                for (byte[] f : frames) wires[i].toClient(f);
                var s = st[i].stats();
                if (s.holes() == 0 && s.pending() == 0 && s.held() > 0) { done[i] = true; remaining--; }
            }
            ticks++;
        }
        // (b) steady state: everyone walks; players that are already done keep ticking like the real 2-tick timer would
        long[] per = new long[n * steadySteps]; int k = 0; long steadyCpu = 0;
        for (int step = 0; step < steadySteps; step++) {
            clock.addAndGet(STEP_NS);
            for (int i = 0; i < n; i++) {
                px[i] += 0.6;
                long c0 = System.nanoTime();
                var frames = st[i].tick(px[i], 0, pz[i]);
                long dt = System.nanoTime() - c0;
                per[k++] = dt; steadyCpu += dt;
                for (byte[] f : frames) wires[i].toClient(f);
            }
        }
        if (!print) return;
        Arrays.sort(per);
        long holes = 0, strikes = 0;
        for (int i = 0; i < n; i++) { holes += st[i].stats().holes(); strikes += clients[i].strikes(); }
        double simSeconds = steadySteps * STEP_NS / 1e9;
        System.out.printf("%d | %.0f (%.0f)%s | %d | %.0f / %.0f / %.0f | %.1f | %d | %d%n",
                n, joinCpu / 1e6, joinCpu / 1e6 / n, remaining > 0 ? " NOT CONVERGED=" + remaining : "", ticks,
                per[per.length / 2] / 1e3, per[(int) (per.length * 0.99)] / 1e3, per[per.length - 1] / 1e3,
                steadyCpu / 1e6 / simSeconds, holes, strikes);
    }

    record Wire(ServerSession server, ClientSession client) {
        void toServer(byte[] f) { for (byte[] r : server.onFrame(f)) toClient(r); }
        void toClient(byte[] f) { for (byte[] r : client.onFrame(f)) toServer(r); }
    }
}
