package dev.awre.paper;

import dev.awre.protocol.Awre;
import dev.awre.protocol.Counters;
import dev.awre.protocol.Provenance;
import dev.awre.protocol.ServerSession;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Bridge between Bukkit plugin messages and the pure-Java {@link ServerSession}s.
 * Threading: Bukkit delivers plugin messages and commands on the main thread. The work done here is bounded and
 * trivial (frames are capped at 32000 bytes BEFORE anything is parsed; one test tile is ~0.6 KB); the handler duration
 * is measured and exposed through /awre stats so "trivial" is evidence, not an assumption.
 */
public final class AwreChannel implements PluginMessageListener, Listener {
    private final JavaPlugin plugin;
    private volatile AwreConfig cfg;
    private final Counters counters = new Counters();
    private final Map<UUID, ServerSession> sessions = new ConcurrentHashMap<>(); // bounded by online players (removed on quit)
    private final SecureRandom random = new SecureRandom();
    private final LongAdder handlerCalls = new LongAdder(), handlerNanos = new LongAdder();
    private final AtomicLong handlerMaxNanos = new AtomicLong();
    private final AtomicLong revisionCounter = new AtomicLong(1);
    private volatile boolean debug;
    private volatile AwreStreaming streaming;

    public AwreChannel(JavaPlugin plugin, AwreConfig cfg) {
        this.plugin = plugin; this.cfg = cfg; this.debug = cfg.debug();
    }

    public void register() {
        var m = plugin.getServer().getMessenger();
        m.registerIncomingPluginChannel(plugin, Awre.CHANNEL, this);
        m.registerOutgoingPluginChannel(plugin, Awre.CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void unregister() {
        var m = plugin.getServer().getMessenger();
        m.unregisterIncomingPluginChannel(plugin, Awre.CHANNEL);
        m.unregisterOutgoingPluginChannel(plugin, Awre.CHANNEL);
        sessions.clear();
    }

    public void reload(AwreConfig next) { this.cfg = next; this.debug = next.debug(); } // applies to NEW sessions
    public AwreConfig config() { return cfg; }
    public Counters counters() { return counters; }
    public void setDebug(boolean on) { this.debug = on; }
    public boolean debug() { return debug; }
    void setStreaming(AwreStreaming s) { this.streaming = s; }
    AwreStreaming streaming() { return streaming; }
    public ServerSession session(Player p) { return sessions.get(p.getUniqueId()); }
    public int sessionCount() { return sessions.size(); }
    public long handlerCalls() { return handlerCalls.sum(); }
    public long handlerAvgNanos() { long c = handlerCalls.sum(); return c == 0 ? 0 : handlerNanos.sum() / c; }
    public long handlerMaxNanos() { return handlerMaxNanos.get(); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
        AwreStreaming st = streaming;
        if (st != null) st.detach(e.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Awre.CHANNEL.equals(channel)) return;
        long t0 = System.nanoTime();
        try {
            counters.inc("plugin.messages.in");
            AwreConfig c = cfg;
            if (!c.enabled()) { counters.inc("plugin.disabled.drop"); return; }
            if (message.length > Awre.HARD_MAX_FRAME) { counters.inc("plugin.in.huge"); return; } // no session, no parsing
            ServerSession s = sessions.computeIfAbsent(player.getUniqueId(),
                    id -> new ServerSession(c.limits(), System::nanoTime, counters, random.nextLong()));
            ServerSession.State before = s.state();
            for (byte[] reply : s.onFrame(message)) send(player, reply);
            if (before != s.state()) {
                plugin.getLogger().info("[AWRE] " + player.getName() + ": session " + before + " -> " + s.state()
                        + (s.state() == ServerSession.State.ACTIVE ? " (protocol v" + s.negotiatedVersion() + ", client mod " + s.clientModVersion() + ")" : " (" + s.lastError() + ")"));
                if (s.state() == ServerSession.State.ACTIVE && c.autoSendTestTile()) sendTestTile(player, s);
                if (s.state() == ServerSession.State.ACTIVE && c.autoFragmentTestSide() > 0) sendNoiseTile(player, s, c.autoFragmentTestSide());
                AwreStreaming st = streaming;
                if (s.state() == ServerSession.State.ACTIVE && c.autoStream() && st != null) st.attach(player, s, c);
            } else if (debug) {
                plugin.getLogger().info("[AWRE] " + player.getName() + " frame handled, state=" + s.state() + " strikes=" + s.strikes());
            }
        } finally {
            long dt = System.nanoTime() - t0;
            handlerCalls.increment(); handlerNanos.add(dt);
            handlerMaxNanos.accumulateAndGet(dt, Math::max);
        }
    }

    /**
     * The dimension id put on the wire: the namespaced world key (e.g. "minecraft:overworld", "minecraft:the_nether"), NOT the Bukkit
     * world name ("world", "world_nether"). The client only knows its level's ResourceKey, and DimensionGate compares against it, so a
     * name here made every tile look like it belonged to another dimension (G2: candidates=0, no mesh ever built).
     */
    static String wireDimension(World w) {
        String d = w.getKey().toString();
        return d.length() > Awre.MAX_DIMENSION_CHARS ? d.substring(0, Awre.MAX_DIMENSION_CHARS) : d;
    }

    /** Sends one deterministic test tile for the player's current chunk. */
    public ServerSession.SendResult sendTestTile(Player player, ServerSession s) {
        World w = player.getWorld();
        UUID u = w.getUID();
        String dim = wireDimension(w);
        int tx = player.getLocation().getBlockX() >> 4, tz = player.getLocation().getBlockZ() >> 4;
        ServerSession.TileSend r = s.buildTestTile(u.getMostSignificantBits(), u.getLeastSignificantBits(), dim, tx, tz, 0,
                revisionCounter.getAndIncrement(), Provenance.AUTHORITATIVE);
        if (r.frame() != null) send(player, r.frame());
        return r.result();
    }

    /** Sends a deterministic noise tile of side x side samples (side 128 = 32 KiB raw, ~25 KiB deflated => fragmented). */
    public ServerSession.TileFrames sendNoiseTile(Player player, ServerSession s, int side) {
        World w = player.getWorld();
        UUID u = w.getUID();
        String dim = wireDimension(w);
        int tx = player.getLocation().getBlockX() >> 4, tz = player.getLocation().getBlockZ() >> 4;
        ServerSession.TileFrames r = s.sendTestTile(u.getMostSignificantBits(), u.getLeastSignificantBits(), dim, tx, tz, 1, // lod 1: a different TileKey than the G0 test tile, so the client logs both
                revisionCounter.getAndIncrement(), Provenance.AUTHORITATIVE, side);
        for (byte[] f : r.frames()) send(player, f);
        plugin.getLogger().info("[AWRE] " + player.getName() + ": noise tile side=" + side + " " + r.result() + ", frames=" + r.frames().size());
        return r;
    }

    public void send(Player player, byte[] frame) {
        try {
            player.sendPluginMessage(plugin, Awre.CHANNEL, frame);
            counters.inc("plugin.messages.out");
        } catch (RuntimeException e) { // MessageTooLargeException / ChannelNotRegisteredException / disabled plugin
            counters.inc("plugin.send.failed");
            if (debug) plugin.getLogger().warning("[AWRE] send failed: " + e);
        }
    }

    /** True if the client announced (via minecraft:register) that it listens on our channel. */
    public boolean clientListens(Player p) { return p.getListeningPluginChannels().contains(Awre.CHANNEL); }
}
