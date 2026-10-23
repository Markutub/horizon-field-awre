package dev.awre.stream;

import dev.awre.protocol.ClientSession;
import dev.awre.protocol.Counters;
import dev.awre.protocol.Limits;
import dev.awre.protocol.TileKey;

/**
 * Everything the client mod does that is NOT Minecraft API: session lifecycle, 5 s log cadence, view/planner ownership,
 * dimension-change handling. Pure Java so it is unit-tested here and so a port to another Minecraft generation only rewrites the
 * thin adapter (event wiring, payload send, the GPU renderer), never this logic.
 * Threading: every method is called on the client thread.
 *
 * @param <H> the renderer's GPU handle type
 */
public final class ClientRuntime<H> {
    /** What the adapter provides. */
    public interface Host {
        void send(byte[] frame);
        void info(String message);
        void warn(String message);
    }

    private static final int START_DELAY_TICKS = 20;   // let minecraft:register go out first (hypothesis; retries cover a miss)

    private final Host host;
    private final MeshResidency.Backend<H> backend;
    private final String modVersion;
    private final double viewMaxDistance;
    private final int freeDelayFrames;
    private final boolean renderEnabled;
    private final Limits limits = Limits.defaults().withClientTileCache(8192);   // terrain streaming holds thousands of tiles
    private final Counters counters = new Counters();

    private ClientSession session;
    private ClientHorizonView view;
    private HorizonDrawPlanner<H> planner;
    private Object lastLevel;                          // identity of the level: changes on dimension change / respawn / disconnect
    private int ticksSinceViewUpdate, ticksSinceViewLog, ticksSinceStoreLog, lastStoreCount = -1;
    private String lastViewLog = "";
    private int sinceJoin = -1;
    private ClientSession.State lastLogged = ClientSession.State.IDLE;
    private TileKey lastTileLogged;

    /**
     * @param renderEnabled false: still speaks the protocol and stores tiles, but builds no meshes and owns no planner (used when a
     *                      conflicting renderer or shader mod is present, or the player turned rendering off)
     * @param freeDelayFrames frames a freed GPU buffer stays open (explicit-API backends such as Vulkan may still be reading it)
     */
    public ClientRuntime(Host host, MeshResidency.Backend<H> backend, String modVersion, double viewMaxDistance, int freeDelayFrames, boolean renderEnabled) {
        this.host = host; this.backend = backend; this.modVersion = modVersion;
        this.viewMaxDistance = viewMaxDistance; this.freeDelayFrames = freeDelayFrames; this.renderEnabled = renderEnabled;
    }

    /** Null when not joined or rendering is disabled. The adapter hands it to its renderer. */
    public HorizonDrawPlanner<H> planner() { return planner; }
    public ClientSession session() { return session; }

    public void onJoin(int protocolMin, int protocolMax) {
        closePlanner();
        session = new ClientSession(limits, counters, modVersion, protocolMin, protocolMax);
        session.setPayloadValidator(TerrainPayloads.ANY);   // G0/G1 test tiles or terrain tiles
        if (renderEnabled) {
            view = new ClientHorizonView(session, 4096);   // meshes are built on a background thread
            planner = new HorizonDrawPlanner<>(view, new MeshResidency<>(backend, MeshResidency.Limits.defaults(), freeDelayFrames));
        }
        lastLevel = null; sinceJoin = 0; lastLogged = ClientSession.State.IDLE; lastTileLogged = null;
        host.info("[AWRE] joined a server; handshake starts in " + START_DELAY_TICKS + " ticks (client v" + protocolMin + ".." + protocolMax
                + (renderEnabled ? ")" : "), rendering disabled"));
    }

    public void onDisconnect() {
        if (session != null) host.info("[AWRE] disconnected; final state " + session.state() + " (" + session.reason() + "), tiles held "
                + session.tileCount() + ", counters " + counters.snapshot());
        session = null;
        closePlanner();
        sinceJoin = -1;
    }

    /** Client stopping: free everything now. */
    public void shutdown() { closePlanner(); session = null; }

    private void closePlanner() {
        if (planner != null) planner.close();               // frees GPU meshes (flushing delayed frees) and closes the view
        planner = null; view = null;
    }

    /**
     * Every client tick.
     * @param level any object whose identity changes when the player changes level; null if none
     * @param dimension namespaced dimension id of the current level, or null if unknown
     */
    public void onTick(boolean playerPresent, Object level, double playerX, double playerZ, double vanillaRadius, String dimension) {
        if (session == null) return;
        updateView(playerPresent, level, playerX, playerZ, vanillaRadius, dimension);
        if (sinceJoin >= 0 && sinceJoin < START_DELAY_TICKS) {
            if (++sinceJoin == START_DELAY_TICKS) host.send(session.start());
            return;
        }
        byte[] retry = session.tick();
        if (retry != null) host.send(retry);
        logTransitions();
        if (session.state() == ClientSession.State.ACTIVE && ++ticksSinceStoreLog >= 100) {   // every 5 s, only when it changed
            ticksSinceStoreLog = 0;
            int n = session.tileCount();
            if (n != lastStoreCount) {
                lastStoreCount = n;
                host.info("[AWRE] store tiles=" + n + " accepted=" + counters.get("client.tile.accepted") + " dropped=" + counters.get("client.tile.dropped")
                        + " palette_entries=" + counters.get("client.palette.entries") + " strikes=" + session.strikes());
            }
        }
    }

    /** 10 Hz: decide which held tiles would be drawn (the planner consumes it; the renderer draws it). */
    private void updateView(boolean playerPresent, Object level, double x, double z, double vanillaRadius, String dimension) {
        ClientHorizonView v = view;
        HorizonDrawPlanner<H> pl = planner;
        if (v == null || pl == null || session.state() != ClientSession.State.ACTIVE || !playerPresent) return;
        if (lastLevel != level) {                           // dimension change / respawn: drop every GPU mesh of the old level
            if (lastLevel != null) { host.info("[AWRE] level changed: releasing GPU meshes"); pl.release(); }
            lastLevel = level;
        }
        if (++ticksSinceViewUpdate >= 2) {
            ticksSinceViewUpdate = 0;
            // the level's own dimension wins; the last received tile is only a fallback (see DimensionGate)
            pl.tick(x, z, vanillaRadius, viewMaxDistance, DimensionGate.filter(dimension, session.lastTile()));
        }
        if (++ticksSinceViewLog >= 100) {                   // every 5 s, only when it changed
            ticksSinceViewLog = 0;
            var s = v.stats();
            String line = "held=" + s.held() + " candidates=" + s.candidates() + " ready=" + s.ready() + " drawn=" + s.drawn()
                    + " hidden_vanilla=" + s.hiddenByVanilla() + " hidden_finer=" + s.hiddenByFiner() + " too_far=" + s.tooFar()
                    + " tris=" + s.triangles() + " pending=" + s.pending() + " failures=" + s.failures()
                    + " over_cap=" + s.overCap() + " vanilla_radius=" + (int) vanillaRadius;   // appended: CI parses the leading fields by prefix
            if (!line.equals(lastViewLog)) { lastViewLog = line; host.info("[AWRE] horizon view: " + line); }
            host.info("[AWRE] render: " + pl.diagnosticsLine());
            if (s.held() > 0 && s.candidates() == 0 && s.hiddenByVanilla() == 0 && s.tooFar() == 0)
                host.warn("[AWRE] " + s.held() + " tiles held but none belong to dimension " + dimension + " (server/client dimension id mismatch?)");
        }
    }

    public void onPayload(byte[] data, int skippedLength) {
        ClientSession s = session;
        if (s == null) return;
        if (skippedLength > 0) {
            s.onOversizeSkipped(skippedLength);
            host.warn("[AWRE] skipped an oversize payload of " + skippedLength + " bytes without allocating it");
        } else {
            for (byte[] reply : s.onFrame(data)) host.send(reply);
        }
        logTransitions();
    }

    private void logTransitions() {
        ClientSession s = session;
        if (s == null) return;
        if (s.state() != lastLogged) {
            lastLogged = s.state();
            switch (s.state()) {
                case ACTIVE -> host.info("[AWRE] handshake OK: protocol v" + s.negotiatedVersion() + ", max frame " + s.negotiatedFrame() + " B, caps " + s.negotiatedCaps()
                        + " (1=tile, 2=fragment, 4=drop, 8=palette), server session " + Long.toHexString(s.sessionId()));
                case INCOMPATIBLE -> host.warn("[AWRE] incompatible server, continuing as a normal vanilla-style client: " + s.reason());
                case FAILED -> host.warn("[AWRE] AWRE unavailable, vanilla fallback: " + s.reason());
                default -> { }
            }
        }
        TileKey t = s.lastTile();
        if (t != null && !t.equals(lastTileLogged)) {
            lastTileLogged = t;
            var rec = s.tile(t);
            host.info("[AWRE] test tile (" + t.tileX() + "," + t.tileZ() + ") lod " + t.lod() + " rev " + rec.revision() + " " + rec.provenance() + " verified OK (" + rec.decoded().length + " decoded bytes)");
        }
    }
}
