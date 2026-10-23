/** Reproduces the CI render-line observation "mesh_built - gpu_uploaded = N" with the REAL server -> client -> view -> planner chain (synthetic terrain, same scale as the integration job: 161x161 chunks, stream radius 1000) and a counting fake GPU. Usage: tools/bench/run-residencysim.sh */
import dev.awre.protocol.*;
import dev.awre.stream.*;
import dev.horizonfield.core.lod.*;
import dev.horizonfield.core.terrain.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Reproduces the "mesh_built - gpu_uploaded = 4" observation with the real server->client->view->planner chain and a counting fake GPU. */
public class ResidencySim {   // args: finalRadiusBlocks [switchTick x0 z0 initialRadiusBlocks]
    static final class Gpu implements MeshResidency.Backend<Integer> {
        int next = 1; final Set<Integer> live = new HashSet<>();
        public Integer upload(TileKey k, TerrainMesher.Mesh m) { live.add(next); return next++; }
        public void free(Integer h) { live.remove(h); }
    }
    public static void main(String[] a) throws Exception {
        double vanilla = a.length > 0 ? Double.parseDouble(a[0]) : 64;       // final client render distance * 16
        int switchTick = a.length > 1 ? Integer.parseInt(a[1]) : 0;          // ticks (50 ms) spent at the initial camera/radius
        double x0 = a.length > 2 ? Double.parseDouble(a[2]) : 0, z0 = a.length > 3 ? Double.parseDouble(a[3]) : 0, v0 = a.length > 4 ? Double.parseDouble(a[4]) : vanilla;
        LodModel model = LodModel.defaults();
        Limits lim = Limits.defaults().withStreaming(16, 64, 500).withClientTileCache(8192);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        Counters sc = new Counters(), cc = new Counters();
        ServerSession server = new ServerSession(lim, clock::get, sc, 77);
        ClientSession client = new ClientSession(lim, cc, "t", Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX);
        TerrainStore store = new TerrainStore(model.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
        SyntheticTerrain.fill(store, -80, -80, 79, 79, 1234);
        var palette = SyntheticTerrain.palette();
        java.util.function.Consumer<byte[]>[] toClient = new java.util.function.Consumer[1];
        java.util.function.Consumer<byte[]> toServer = f -> { for (byte[] r : server.onFrame(f)) toClient[0].accept(r); };
        toClient[0] = f -> { for (byte[] r : client.onFrame(f)) toServer.accept(r); };
        client.setPayloadValidator(TerrainPayloads.TERRAIN);
        toServer.accept(client.start());
        PlayerStreamer s = new PlayerStreamer(server, store, palette, new LodPlanner(model), new StreamConfig(1000, 400_000, 65_536, 0.1, 4096, 16, 256, 3), 11, 22, "minecraft:overworld", clock::get);

        Gpu gpu = new Gpu();
        ClientHorizonView view = new ClientHorizonView(client, 4096);
        HorizonDrawPlanner<Integer> planner = new HorizonDrawPlanner<>(view, new MeshResidency<>(gpu, MeshResidency.Limits.defaults()));
        int frames = 0;
        for (int tick = 0; tick < 1500; tick++) {
            clock.addAndGet(50_000_000L);
            double cx = tick < switchTick ? x0 : 0, cz = tick < switchTick ? z0 : 0, cv = tick < switchTick ? v0 : vanilla;
            for (byte[] f : s.tick(cx, cz, 0)) toClient[0].accept(f);
            planner.tick(cx, cz, cv, 16384, null);
            for (int i = 0; i < 3; i++) { planner.frame(cx, 100, cz); frames++; }
            if (tick % 5 == 0) Thread.sleep(2);
        }
        Thread.sleep(300);
        for (int i = 0; i < 50; i++) { planner.tick(0, 0, vanilla, 16384, null); planner.frame(0, 100, 0); Thread.sleep(10); }
        System.out.println("vanilla radius " + vanilla + " blocks: " + planner.diagnosticsLine());
        var vs = view.stats();
        System.out.println("view: held=" + vs.held() + " candidates=" + vs.candidates() + " ready=" + vs.ready() + " hidden_vanilla=" + vs.hiddenByVanilla() + " hidden_finer=" + vs.hiddenByFiner() + " too_far=" + vs.tooFar() + " drawn=" + vs.drawn() + " built(lifetime)=" + vs.built());
        List<TileKey> nr = planner.builtNotResident();
        Set<TileKey> held = client.tileKeys();
        int coveredByChildren = 0;
        for (TileKey k : nr) {
            boolean allChildren = k.lod() > 0;
            for (int dx = 0; dx < 2 && allChildren; dx++) for (int dz = 0; dz < 2 && allChildren; dz++)
                if (!view.meshedKeys().contains(new TileKey(k.worldMsb(), k.worldLsb(), k.dimension(), k.tileX() * 2 + dx, k.tileZ() * 2 + dz, k.lod() - 1))) allChildren = false;
            if (allChildren) coveredByChildren++;
        }
        System.out.println("built_not_resident=" + nr.size() + "  of which all 4 children built (=> hidden parent): " + coveredByChildren + "  first=" + nr.subList(0, Math.min(6, nr.size())).stream().map(k -> k.lod() + "/" + k.tileX() + "/" + k.tileZ()).toList());
        System.out.println("lifetime: uploads - built = " + (vs.built() - planner.diagnostics().uploads()));
        view.close();
    }
}
