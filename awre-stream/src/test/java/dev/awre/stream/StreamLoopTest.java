package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.Awre;
import dev.awre.protocol.ClientSession;
import dev.awre.protocol.Counters;
import dev.awre.protocol.Limits;
import dev.awre.protocol.MessageType;
import dev.awre.protocol.ServerSession;
import dev.awre.protocol.TileKey;
import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.lod.LodModel;
import dev.horizonfield.core.lod.LodPlanner;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import dev.horizonfield.core.terrain.TerrainStore;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Server session + PlayerStreamer + client session over an in-memory wire with a fake clock (50 ms per tick). */
class StreamLoopTest {
    private static final String DIM = "minecraft:overworld";
    private static final long MSB = 11, LSB = 22, SEED = 1234;

    private final LodModel model = LodModel.defaults();
    private final Limits lim = Limits.defaults().withStreaming(16, 64, 500).withClientTileCache(8192);
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final Counters sc = new Counters(), cc = new Counters();
    private final ServerSession server = new ServerSession(lim, clock::get, sc, 77);
    private final ClientSession client = new ClientSession(lim, cc, "t", Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX);
    private final TerrainStore store = new TerrainStore(model.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
    private final dev.horizonfield.core.MaterialPalette palette = SyntheticTerrain.palette();
    private boolean dropAcks;
    private int maxInFlightSeen;

    private void toServer(byte[] f) { for (byte[] r : server.onFrame(f)) toClient(r); }
    private void toClient(byte[] f) { for (byte[] r : client.onFrame(f)) if (!dropAcks) toServer(r); }

    private PlayerStreamer streamer(StreamConfig cfg) {
        client.setPayloadValidator(TerrainPayloads.TERRAIN);
        toServer(client.start());
        assertEquals(ServerSession.State.ACTIVE, server.state());
        assertEquals(Awre.CAPS_ALL, client.negotiatedCaps());
        return new PlayerStreamer(server, store, palette, new LodPlanner(model), cfg, MSB, LSB, DIM, clock::get);
    }

    private List<byte[]> step(PlayerStreamer s, double x, double z, double heading) {
        clock.addAndGet(50_000_000L);
        List<byte[]> frames = s.tick(x, z, heading);
        for (byte[] f : frames) toClient(f);
        maxInFlightSeen = Math.max(maxInFlightSeen, server.inFlight());
        return frames;
    }

    private static boolean contains(TileKey k, LodModel m, double x, double z) {
        double s = m.tileSize(k.lod());
        return x >= k.tileX() * s && x < (k.tileX() + 1) * s && z >= k.tileZ() * s && z < (k.tileZ() + 1) * s;
    }

    private boolean covered(Set<TileKey> keys, double x, double z) {
        for (TileKey k : keys) if (contains(k, model, x, z)) return true;
        return false;
    }

    private StreamConfig cfg(double radius, int maxHeld) { return new StreamConfig(radius, 400_000, 65_536, 0.1, maxHeld, 16, 256, 3); }

    @Test void convergesToExactlyThePlannedLeavesWithCorrectContent() throws IOException {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        PlayerStreamer s = streamer(cfg(700, 4096));
        int ticks = 0;
        while (ticks++ < 3000) {
            step(s, 0, 0, 0);
            PlayerStreamer.Stats st = s.stats();
            if (st.holes() == 0 && st.pending() == 0 && server.inFlight() == 0 && st.sent() > 0 && client.tileCount() == s.plan().leaves().size()) break;
        }
        assertTrue(ticks < 3000, "did not converge: " + s.stats());
        Set<TileKey> expect = new HashSet<>();
        for (LodKey k : s.plan().leaves()) expect.add(new TileKey(MSB, LSB, DIM, k.tx(), k.tz(), k.lod()));
        assertEquals(expect, client.tileKeys());
        for (LodKey k : s.plan().leaves()) {
            var got = client.tile(new TileKey(MSB, LSB, DIM, k.tx(), k.tz(), k.lod()));
            assertTrue(got.revision() == store.version(k), "revision " + k);
            LodTile t = LodTileCodec.decode(k, got.decoded());
            assertTrue(t.sameContent(store.tile(k)), "content " + k);
        }
        assertEquals(palette.snapshot(), client.palette(MSB, LSB, DIM), "client palette");
        for (LodKey k : s.plan().leaves()) {
            LodTile t = store.tile(k);
            for (int i = 0; i < 256; i++) assertTrue(t.materialAt(i % 16, i / 16) < client.palette(MSB, LSB, DIM).size(), "material id outside the client palette");
        }
        TileMeshCache meshes = new TileMeshCache(client, 5000);
        long tris = 0;
        for (TileKey k : client.tileKeys()) { var mesh = meshes.get(k); assertNotNull(mesh, "mesh " + k); tris += mesh.triangleCount(); }
        assertEquals(client.tileCount(), meshes.misses());
        for (TileKey k : client.tileKeys()) meshes.get(k);
        assertEquals(client.tileCount(), meshes.hits(), "second pass must be all cache hits");
        assertEquals(0, meshes.failures());
        System.out.println("meshes: " + client.tileCount() + " tiles -> " + tris + " triangles (" + tris / client.tileCount() + " per tile)");
        assertEquals(0, client.strikes());
        assertEquals(0, server.strikes());
        assertTrue(maxInFlightSeen <= lim.maxInFlightTiles(), "in flight " + maxInFlightSeen);
        double seconds = ticks * 0.05;
        assertTrue(s.stats().wireBytes() <= 65_536 + 400_000 * seconds + 2000, "wire " + s.stats().wireBytes() + " in " + seconds + " s");
        System.out.println("converged in " + ticks + " ticks (" + String.format("%.1f", seconds) + " s sim): " + s.stats());
    }

    @Test void walkingNeverOpensAHoleInTheClientsCoverage() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        PlayerStreamer s = streamer(cfg(400, 4096));
        Random rnd = new Random(3);
        double x = -300;
        for (int tick = 0; tick < 400; tick++) {
            x += 1.0;                                                   // 20 blocks/s
            Set<TileKey> before = client.tileKeys();
            step(s, x, 0, 0);
            Set<TileKey> after = client.tileKeys();
            for (int i = 0; i < 200; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * 390;
                double px = x + Math.cos(a) * d, pz = Math.sin(a) * d;
                if (covered(before, px, pz)) assertTrue(covered(after, px, pz), "tick " + tick + ": hole at " + px + "," + pz);
            }
        }
        assertEquals(0, client.strikes());
        assertTrue(s.stats().dropped() > 0, "walking 400 blocks must drop something");
        assertTrue(client.tileCount() <= 4096);
        System.out.println("walk: " + s.stats() + " client tiles=" + client.tileCount());
    }

    @Test void shadowMatchesTheClientStoreExactly() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        PlayerStreamer s = streamer(cfg(400, 4096));
        double x = -300;
        for (int tick = 0; tick < 300; tick++) {
            x += 1.0;
            step(s, x, 0, 0);
            assertEquals(s.stats().held(), client.tileCount(), "tick " + tick + " (server shadow vs client store)");
        }
    }

    @Test void editedChunksArePushedAsNewRevisions() throws IOException {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        PlayerStreamer s = streamer(cfg(500, 4096));
        for (int i = 0; i < 2000 && !(s.stats().holes() == 0 && s.stats().pending() == 0 && s.stats().sent() > 0 && server.inFlight() == 0); i++) step(s, 0, 0, 0);
        long sentBefore = s.stats().sent();
        short[] top = new short[256]; java.util.Arrays.fill(top, (short) 222);
        store.put(new ColumnTile(0, 0, top, new short[256], dev.horizonfield.core.Confidence.OBSERVED, 5));
        LodKey leaf = null;
        for (LodKey k : s.plan().leaves()) if (k.contains(new LodKey(0, 0, 0))) leaf = k;
        assertNotNull(leaf);
        for (int i = 0; i < 400 && client.tile(new TileKey(MSB, LSB, DIM, leaf.tx(), leaf.tz(), leaf.lod())).revision() != store.version(leaf); i++) step(s, 0, 0, 0);
        var got = client.tile(new TileKey(MSB, LSB, DIM, leaf.tx(), leaf.tz(), leaf.lod()));
        assertEquals(store.version(leaf), got.revision());
        assertTrue(LodTileCodec.decode(leaf, got.decoded()).sameContent(store.tile(leaf)));
        assertEquals(1, s.stats().sent() - sentBefore, "only the affected leaf should be re-sent");
    }

    @Test void lostAcksAreRetriedAfterTheTimeout() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        PlayerStreamer s = streamer(cfg(300, 4096));
        dropAcks = true;
        for (int i = 0; i < 200; i++) step(s, 0, 0, 0);                    // client receives tiles but the server never hears
        assertTrue(server.inFlight() > 0);
        clock.addAndGet(31_000_000_000L);                                   // past the 30 s in-flight timeout
        dropAcks = false;
        int ticks = 0;
        while (ticks++ < 3000) {
            step(s, 0, 0, 0);
            PlayerStreamer.Stats st = s.stats();
            if (st.holes() == 0 && st.pending() == 0 && server.inFlight() == 0 && client.tileCount() == s.plan().leaves().size()) break;
        }
        assertTrue(ticks < 3000, "did not recover: " + s.stats());
        assertTrue(s.stats().failures() > 0, "expiries must have been counted");
        assertEquals(0, client.strikes());
    }

    @Test void heldTilesNeverExceedTheConfiguredCap() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        PlayerStreamer s = streamer(cfg(700, 100));                         // plan needs far more than 100 tiles
        for (int i = 0; i < 1500; i++) {
            step(s, 0, 0, 0);
            assertTrue(s.stats().held() + s.stats().pending() <= 100, "tick " + i + " " + s.stats());
            assertTrue(client.tileCount() <= 100);
        }
        assertTrue(s.stats().holes() > 0, "a too-small cap must leave holes, not overflow");
    }

    @Test void nothingIsSentWhereNoTerrainWasObserved() {
        PlayerStreamer s = streamer(cfg(500, 4096));
        for (int i = 0; i < 50; i++) {
            for (byte[] f : step(s, 0, 0, 0)) assertEquals(MessageType.PALETTE, dev.awre.protocol.Frame.decode(f, lim.maxFrameBytes()).type(), "only the palette may be sent without terrain");
        }
        assertEquals(s.plan().leaves().size(), s.stats().noData());
        assertEquals(0, s.stats().sent());
    }

    @Test void clientsThatNeverAckStopBeingFedAfterTheRetryLimit() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        PlayerStreamer s = streamer(new StreamConfig(300, 400_000, 65_536, 0.1, 4096, 16, 256, 2));
        dropAcks = true;
        long lastSent = -1;
        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 60; i++) step(s, 0, 0, 0);
            clock.addAndGet(31_000_000_000L);
            lastSent = s.stats().sent();
        }
        long after = s.stats().sent();
        for (int i = 0; i < 60; i++) step(s, 0, 0, 0);
        clock.addAndGet(31_000_000_000L);
        for (int i = 0; i < 60; i++) step(s, 0, 0, 0);
        assertTrue(s.stats().sent() - after < s.plan().leaves().size(), "retries must be bounded: " + s.stats() + " leaves=" + s.plan().leaves().size());
        assertTrue(lastSent > 0);
    }

    private long sentToNeverAckingClient(PlayerStreamer s, int rounds) {
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < 60; i++) step(s, 0, 0, 0);
            clock.addAndGet(31_000_000_000L);                                // lets in-flight tiles expire (= failures)
        }
        return s.stats().sent();
    }

    @Test void aBlacklistedTileIsRetriedAfterTheCooldownWithBackoff() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        StreamConfig c = new StreamConfig(300, 400_000, 65_536, 0.1, 4096, 16, 256, 2);
        long cooldown = 100_000_000_000L;                                    // 100 s
        PlayerStreamer s = new PlayerStreamer(server, store, palette, new LodPlanner(model), c, MSB, LSB, DIM, clock::get, cooldown);
        client.setPayloadValidator(TerrainPayloads.TERRAIN); toServer(client.start());
        dropAcks = true;
        long blacklisted = sentToNeverAckingClient(s, 8);                    // enough expiries to reach the retry limit everywhere
        long plateau = sentToNeverAckingClient(s, 1);                        // nothing new while blacklisted and the cooldown has not elapsed
        assertEquals(blacklisted, plateau, "blacklisted tiles must not be re-sent before the cooldown");
        clock.addAndGet(cooldown);                                           // cooldown elapsed
        long afterCooldown = sentToNeverAckingClient(s, 1);
        assertTrue(afterCooldown > plateau, "tiles must be retried after the cooldown, not only after a reconnect");
    }

    @Test void aNewRevisionClearsTheBlacklistImmediately() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        StreamConfig c = new StreamConfig(300, 400_000, 65_536, 0.1, 4096, 16, 256, 2);
        PlayerStreamer s = new PlayerStreamer(server, store, palette, new LodPlanner(model), c, MSB, LSB, DIM, clock::get, 1_000_000_000_000L);
        client.setPayloadValidator(TerrainPayloads.TERRAIN); toServer(client.start());
        dropAcks = true;
        long plateau = sentToNeverAckingClient(s, 8);
        assertEquals(plateau, sentToNeverAckingClient(s, 1));
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED + 1);            // content changes: new revisions everywhere
        assertTrue(sentToNeverAckingClient(s, 1) > plateau, "changed tiles are a different payload and must be sent again");
    }

    @Test void nonPositiveCooldownIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerStreamer(server, store, palette, new LodPlanner(model), StreamConfig.defaults(), MSB, LSB, DIM, clock::get, 0));
    }

    // ---- PHASE regression: vanilla clients, reconnects, corrupt tiles -------------------------------------------------------

    @Test void aVanillaClientThatNeverHandshakesGetsNoAwreFramesAndNoState() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        // no client.start(): the server session stays NEW, exactly like a vanilla client that ignores the plugin channel
        PlayerStreamer s = new PlayerStreamer(server, store, palette, new LodPlanner(model), cfg(300, 4096), MSB, LSB, DIM, clock::get);
        for (int i = 0; i < 300; i++) {
            clock.addAndGet(50_000_000L);
            assertTrue(s.tick(0, 0, 0).isEmpty(), "tick " + i + ": a non-AWRE client must receive nothing");
        }
        assertTrue(server.state() != ServerSession.State.ACTIVE);
        assertEquals(0, s.stats().sent()); assertEquals(0, s.stats().wireBytes());
        assertEquals(0, s.stats().pending(), "nothing may be queued for a client that never negotiated");
    }

    @Test void aReconnectedClientGetsEverythingAgainFromTheSameStoreWithoutStrikes() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        PlayerStreamer first = streamer(cfg(300, 4096));
        for (int i = 0; i < 40; i++) step(first, 0, 0, 0);                  // disconnect part-way through delivery
        assertTrue(client.tileCount() > 0 && client.tileCount() < first.plan().leaves().size(), "premise: delivery was partial");

        // reconnect = brand new sessions + streamer over the SAME server-side store
        Counters sc2 = new Counters(), cc2 = new Counters();
        ServerSession server2 = new ServerSession(lim, clock::get, sc2, 78);
        ClientSession client2 = new ClientSession(lim, cc2, "t", Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX);
        client2.setPayloadValidator(TerrainPayloads.TERRAIN);
        class Wire {
            void toServer(byte[] f) { for (byte[] r : server2.onFrame(f)) toClient(r); }
            void toClient(byte[] f) { for (byte[] r : client2.onFrame(f)) toServer(r); }
        }
        Wire w2 = new Wire();
        w2.toServer(client2.start());
        assertEquals(ServerSession.State.ACTIVE, server2.state());
        PlayerStreamer second = new PlayerStreamer(server2, store, palette, new LodPlanner(model), cfg(300, 4096), MSB, LSB, DIM, clock::get);
        int ticks = 0;
        do {
            clock.addAndGet(50_000_000L);
            for (byte[] f : second.tick(0, 0, 0)) w2.toClient(f);
        } while (++ticks < 3000 && client2.tileCount() < second.plan().leaves().size());
        assertEquals(second.plan().leaves().size(), client2.tileCount(), "the new session must receive the full plan again: " + second.stats());
        assertEquals(0, client2.strikes());
    }

    @Test void aValidFrameWithACorruptTileBodyIsRejectedWithoutKillingTheSessionOrBlockingGoodTiles() {
        SyntheticTerrain.fill(store, -48, -48, 47, 47, SEED);
        PlayerStreamer s = streamer(cfg(300, 4096));
        byte[] garbage = new byte[64]; new Random(9).nextBytes(garbage);
        var bad = dev.awre.protocol.TileMessage.create(MSB, LSB, DIM, 5000, 5000, 0, 1, dev.awre.protocol.Provenance.AUTHORITATIVE, dev.awre.protocol.Encoding.RAW, garbage);
        for (byte[] f : server.sendTile(bad).frames()) toClient(f);
        assertEquals(0, client.tileCount(), "a corrupt body must never be stored");
        assertTrue(server.state() != ServerSession.State.BLOCKED);
        assertEquals(ServerSession.State.ACTIVE, server.state());
        for (int i = 0; i < 600; i++) step(s, 0, 0, 0);                    // good tiles still flow afterwards
        assertTrue(client.tileCount() > 0, "good tiles must still be accepted after a corrupt one");
    }
}
