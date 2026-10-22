package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.awre.protocol.TileKey;
import dev.horizonfield.core.terrain.TerrainMesher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure client lifecycle that used to live inside the Fabric mod class. */
class ClientRuntimeTest {
    private static final class Host implements ClientRuntime.Host {
        final List<byte[]> sent = new ArrayList<>(); final List<String> logs = new ArrayList<>();
        @Override public void send(byte[] f) { sent.add(f); }
        @Override public void info(String m) { logs.add(m); }
        @Override public void warn(String m) { logs.add("WARN " + m); }
    }
    private static final MeshResidency.Backend<Integer> GPU = new MeshResidency.Backend<>() {
        @Override public Integer upload(TileKey k, TerrainMesher.Mesh m) { return 1; }
        @Override public void free(Integer h) { }
    };

    @Test void handshakeStartsAfterTheDelayAndDisconnectIsClean() {
        Host h = new Host();
        ClientRuntime<Integer> rt = new ClientRuntime<>(h, GPU, "t", 16384, 3, true);
        rt.onJoin(1, 1);
        assertTrue(rt.planner() != null && rt.session() != null);
        for (int i = 0; i < 19; i++) rt.onTick(true, "L", 0, 0, 128, "minecraft:overworld");
        assertEquals(0, h.sent.size(), "nothing is sent before the start delay");
        rt.onTick(true, "L", 0, 0, 128, "minecraft:overworld");
        assertEquals(1, h.sent.size(), "the hello goes out on the 20th tick");
        rt.onDisconnect(); rt.onDisconnect();                        // idempotent
        assertTrue(rt.planner() == null && rt.session() == null);
        rt.onTick(true, "L", 0, 0, 128, null);                       // no session: ignored
        rt.shutdown();
    }

    @Test void renderDisabledStillSpeaksTheProtocolButOwnsNoPlanner() {
        Host h = new Host();
        ClientRuntime<Integer> rt = new ClientRuntime<>(h, GPU, "t", 16384, 3, false);
        rt.onJoin(1, 1);
        assertTrue(rt.planner() == null && rt.session() != null);
        for (int i = 0; i < 20; i++) rt.onTick(true, "L", 0, 0, 128, "minecraft:overworld");
        assertEquals(1, h.sent.size());
        assertTrue(h.logs.stream().anyMatch(s -> s.contains("rendering disabled")));
        rt.onDisconnect();
    }

    @Test void rejoiningManyTimesLeavesNothingBehind() {
        Host h = new Host();
        ClientRuntime<Integer> rt = new ClientRuntime<>(h, GPU, "t", 16384, 3, true);
        for (int i = 0; i < 50; i++) { rt.onJoin(1, 1); rt.onTick(true, "L" + i, 0, 0, 128, "minecraft:overworld"); rt.onDisconnect(); }
        assertTrue(rt.planner() == null);
    }
}
