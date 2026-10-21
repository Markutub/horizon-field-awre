package dev.awre.fabric;

import dev.awre.protocol.Awre;
import dev.awre.protocol.ClientSession;
import dev.awre.protocol.Counters;
import dev.awre.protocol.Limits;
import dev.awre.protocol.TileKey;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * G0 client: negotiates the awre:main protocol after joining a server and verifies one test tile. Renders nothing.
 * Everything protocol-related lives in the pure-Java ClientSession (unit tested); this class only wires Fabric events.
 * Test hooks (JVM properties): -Dawre.test.minVersion=2 -Dawre.test.maxVersion=3 forces an incompatible client.
 */
public final class AwreClientMod implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("AWRE");
    private static final String MOD_VERSION = "0.1.0";
    private static final int START_DELAY_TICKS = 20;   // let minecraft:register go out first (hypothesis; retries cover a miss)

    private final Limits limits = Limits.defaults().withClientTileCache(8192);   // terrain streaming holds thousands of tiles
    private final Counters counters = new Counters();
    private ClientSession session;
    private dev.awre.stream.ClientHorizonView view;
    private int ticksSinceViewUpdate, ticksSinceViewLog;
    private String lastViewLog = "";
    // distance (blocks) beyond which held tiles are not drawn; -Dawre.view.maxDistance=... to change
    private final double viewMaxDistance = Double.parseDouble(System.getProperty("awre.view.maxDistance", "16384"));
    private int sinceJoin = -1;
    private ClientSession.State lastLogged = ClientSession.State.IDLE;
    private TileKey lastTileLogged;
    private int ticksSinceStoreLog, lastStoreCount = -1;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(AwrePayload.TYPE, AwrePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AwrePayload.TYPE, AwrePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(AwrePayload.TYPE, (payload, context) -> onPayload(payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick(client));
        LOG.info("[AWRE] client G0 loaded; protocol v{}..v{}, channel {}", Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX, Awre.CHANNEL);
    }

    private void onJoin() {
        int min = Integer.getInteger("awre.test.minVersion", Awre.PROTOCOL_MIN);
        int max = Integer.getInteger("awre.test.maxVersion", Awre.PROTOCOL_MAX);
        session = new ClientSession(limits, counters, MOD_VERSION, min, max);
        session.setPayloadValidator(dev.awre.stream.TerrainPayloads.ANY);   // G0/G1 test tiles or terrain tiles
        if (view != null) view.close();
        view = new dev.awre.stream.ClientHorizonView(session, 4096);        // meshes are built on a background thread; nothing is drawn yet
        sinceJoin = 0;
        lastLogged = ClientSession.State.IDLE;
        lastTileLogged = null;
        LOG.info("[AWRE] joined a server; handshake starts in {} ticks (client v{}..v{})", START_DELAY_TICKS, min, max);
    }

    private void onDisconnect() {
        if (session != null) LOG.info("[AWRE] disconnected; final state {} ({}), tiles held {}, counters {}",
                session.state(), session.reason(), session.tileCount(), counters.snapshot());
        session = null;
        if (view != null) { view.close(); view = null; }
        sinceJoin = -1;
    }

    private void onTick(net.minecraft.client.Minecraft client) {
        if (session == null) return;
        updateView(client);
        if (sinceJoin >= 0 && sinceJoin < START_DELAY_TICKS) {
            if (++sinceJoin == START_DELAY_TICKS) send(session.start());
            return;
        }
        byte[] retry = session.tick();
        if (retry != null) send(retry);
        logTransitions();
        if (session.state() == ClientSession.State.ACTIVE && ++ticksSinceStoreLog >= 100) {   // every 5 s, only when it changed
            ticksSinceStoreLog = 0;
            int n = session.tileCount();
            if (n != lastStoreCount) {
                lastStoreCount = n;
                LOG.info("[AWRE] store tiles={} accepted={} dropped={} palette_entries={} strikes={}", n, counters.get("client.tile.accepted"), counters.get("client.tile.dropped"), counters.get("client.palette.entries"), session.strikes());
            }
        }
    }

    /** 10 Hz: decide which held tiles would be drawn (a GPU renderer will consume this list; today it is only counted and logged). */
    private void updateView(net.minecraft.client.Minecraft client) {
        dev.awre.stream.ClientHorizonView v = view;
        if (v == null || session.state() != ClientSession.State.ACTIVE || client.player == null) return;
        if (++ticksSinceViewUpdate >= 2) {
            ticksSinceViewUpdate = 0;
            TileKey last = session.lastTile();
            double vanilla = client.options.renderDistance().get() * 16.0;
            v.update(client.player.getX(), client.player.getZ(), vanilla, viewMaxDistance,
                    last == null ? null : k -> k.dimension().equals(last.dimension()));
        }
        if (++ticksSinceViewLog >= 100) {                      // every 5 s, only when it changed
            ticksSinceViewLog = 0;
            var s = v.stats();
            String line = "held=" + s.held() + " candidates=" + s.candidates() + " ready=" + s.ready() + " drawn=" + s.drawn()
                    + " hidden_vanilla=" + s.hiddenByVanilla() + " hidden_finer=" + s.hiddenByFiner() + " too_far=" + s.tooFar()
                    + " tris=" + s.triangles() + " pending=" + s.pending() + " failures=" + s.failures();
            if (!line.equals(lastViewLog)) { lastViewLog = line; LOG.info("[AWRE] horizon view: {}", line); }
        }
    }

    private void onPayload(AwrePayload p) {
        ClientSession s = session;
        if (s == null) return;
        if (p.skippedLength() > 0) {
            s.onOversizeSkipped(p.skippedLength());
            LOG.warn("[AWRE] skipped an oversize payload of {} bytes without allocating it", p.skippedLength());
        } else {
            for (byte[] reply : s.onFrame(p.data())) send(reply);
        }
        logTransitions();
    }

    private void logTransitions() {
        ClientSession s = session;
        if (s == null) return;
        if (s.state() != lastLogged) {
            lastLogged = s.state();
            switch (s.state()) {
                case ACTIVE -> LOG.info("[AWRE] handshake OK: protocol v{}, max frame {} B, caps {} (1=tile, 2=fragment, 4=drop, 8=palette), server session {}", s.negotiatedVersion(), s.negotiatedFrame(), s.negotiatedCaps(), Long.toHexString(s.sessionId()));
                case INCOMPATIBLE -> LOG.warn("[AWRE] incompatible server, continuing as a normal vanilla-style client: {}", s.reason());
                case FAILED -> LOG.warn("[AWRE] AWRE unavailable, vanilla fallback: {}", s.reason());
                default -> { }
            }
        }
        TileKey t = s.lastTile();
        if (t != null && !t.equals(lastTileLogged)) {
            lastTileLogged = t;
            var rec = s.tile(t);
            LOG.info("[AWRE] test tile ({},{}) lod {} rev {} {} verified OK ({} decoded bytes)", t.tileX(), t.tileZ(), t.lod(), rec.revision(), rec.provenance(), rec.decoded().length);
        }
    }

    private void send(byte[] frame) {
        try {
            ClientPlayNetworking.send(new AwrePayload(frame, 0));
        } catch (RuntimeException e) {
            LOG.warn("[AWRE] send failed: {}", e.toString());
        }
    }
}
