/** After the whole plan is streamed, how many 10 Hz updates (game time) until every candidate tile is meshed, per maxInFlight (synthetic terrain, 161x161 chunks, vanilla radius 192). Models the mesher thread with a queue drained between updates. Usage: tools/bench/run-buildratesim.sh */
import dev.awre.protocol.*;
import dev.awre.stream.*;
import dev.horizonfield.core.lod.*;
import dev.horizonfield.core.terrain.*;
import java.util.concurrent.atomic.AtomicLong;

/** After the whole plan is streamed, how many 10 Hz updates (and how much real mesher time) until every candidate tile is meshed, per maxInFlight. */
public class BuildRateSim {
    public static void main(String[] a) throws Exception {
        LodModel model = LodModel.defaults();
        Limits lim = Limits.defaults().withStreaming(16, 64, 500).withClientTileCache(8192);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        ServerSession server = new ServerSession(lim, clock::get, new Counters(), 77);
        ClientSession client = new ClientSession(lim, new Counters(), "t", Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX);
        TerrainStore store = new TerrainStore(model.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
        SyntheticTerrain.fill(store, -80, -80, 79, 79, 1234);
        java.util.function.Consumer<byte[]>[] toClient = new java.util.function.Consumer[1];
        java.util.function.Consumer<byte[]> toServer = f -> { for (byte[] r : server.onFrame(f)) toClient[0].accept(r); };
        toClient[0] = f -> { for (byte[] r : client.onFrame(f)) toServer.accept(r); };
        client.setPayloadValidator(TerrainPayloads.TERRAIN);
        toServer.accept(client.start());
        PlayerStreamer s = new PlayerStreamer(server, store, SyntheticTerrain.palette(), new LodPlanner(model), new StreamConfig(1000, 400_000, 65_536, 0.1, 4096, 16, 256, 3), 11, 22, "minecraft:overworld", clock::get);
        for (int t = 0; t < 1500; t++) { clock.addAndGet(50_000_000L); for (byte[] f : s.tick(0, 0, 0)) toClient[0].accept(f); }
        System.out.println("streamed tiles held by the client: " + client.tileKeys().size() + "  (vanilla radius 192 blocks, stationary camera)");
        for (int inFlight : new int[]{4, 8, 16, 32, 64, 128}) {
            java.util.ArrayDeque<Runnable> q = new java.util.ArrayDeque<>();   // stands in for the mesher thread: it drains its queue long before the next 100 ms update
            ClientHorizonView v = new ClientHorizonView(ClientHorizonView.TileSource.of(client), 4096, inFlight, q::add);
            int ticks = 0, cand = 0; long t0 = System.nanoTime();
            while (ticks < 5000) {
                v.update(0, 0, 192, 16384, null); ticks++;
                while (!q.isEmpty()) q.poll().run();
                cand = v.stats().candidates();
                if (cand > 0 && v.meshedKeys().size() >= cand) break;
            }
            double wallMs = (System.nanoTime() - t0) / 1e6;
            System.out.printf("maxInFlight=%3d: %4d candidates meshed after %4d updates = %5.1f s of game time at 10 Hz (%4.0f tiles/s); total mesher CPU here %.0f ms (%.2f ms/tile)%n", inFlight, cand, ticks, ticks / 10.0, cand / (ticks / 10.0), wallMs, wallMs / cand);
            v.close();
        }
    }
}
