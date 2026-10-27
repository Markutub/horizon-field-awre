package dev.awre.paper;

import dev.awre.protocol.ServerSession;
import dev.awre.stream.JsonlLog;
import dev.awre.stream.PlayerStreamer;
import dev.awre.stream.ReportJson;
import dev.horizonfield.core.TimingWindow;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Records RESULTS to disk: one JSON line per report in plugins/AWRE/reports/awre-report.jsonl (rotated at 8 MiB, one backup), so a test run leaves
 * numbers behind instead of only log lines and chat messages. Written every {@code diagnostics.report-seconds} (0 = no timer), on {@code /awre report},
 * and once at shutdown when the timer is on. The snapshot is assembled on the calling (main) thread (small maps, no world access) and written off-thread.
 * Not reported: anything from clients. The wire protocol has no client-statistics message, so client-side results exist only in the client's own log.
 * API-ASSUMPTION (core Bukkit, not yet exercised elsewhere in this package): Server#getOnlinePlayers(), Server#getVersion().
 */
final class AwreReport {
    static final int SCHEMA = 1;
    private static final long MAX_BYTES = 8L << 20;
    private static final int MAX_PLAYERS = 200;
    private final JavaPlugin plugin;
    private final AwreChannel channel;
    private final AwreStreaming streaming;
    private final AwreMspt mspt;                       // null when diagnostics.mspt-seconds = 0
    private final int seconds;
    private final JsonlLog log;
    private BukkitTask task;
    private final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();

    AwreReport(JavaPlugin plugin, AwreChannel channel, AwreStreaming streaming, AwreMspt mspt, int seconds) {
        this.plugin = plugin; this.channel = channel; this.streaming = streaming; this.mspt = mspt; this.seconds = seconds;
        this.log = new JsonlLog(plugin.getDataFolder().toPath().resolve("reports").resolve("awre-report.jsonl"), MAX_BYTES);
    }

    Path file() { return log.file(); }

    void start() {
        if (seconds <= 0) return;
        long ticks = 20L * seconds;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> write("timer", true), ticks, ticks);
        plugin.getLogger().info("[AWRE] result reports on: one JSON line every " + seconds + " s -> " + log.file().toAbsolutePath());
    }

    /** Called from onDisable (before streaming stops, so the numbers are still there). Scheduling is not allowed while disabling: written inline. */
    void stop() {
        if (task != null) task.cancel();
        if (seconds > 0) write("shutdown", false);
    }

    /** /awre report: appends one snapshot now. */
    void writeNow() { write("command", true); }

    private void write(String reason, boolean async) {
        final String line;
        try { line = ReportJson.toJson(build(reason)); }
        catch (RuntimeException e) { fail(e); return; }
        Runnable io = () -> {
            try { log.append(line); failures.set(0); }
            catch (IOException | RuntimeException e) { fail(e); }
        };
        if (async) plugin.getServer().getScheduler().runTaskAsynchronously(plugin, io); else io.run();
    }

    private void fail(Exception e) {
        // a broken disk must not flood the log: first failure and then every 20th
        if (failures.getAndIncrement() % 20 == 0) plugin.getLogger().warning("[AWRE] could not write the result report (" + log.file() + "): " + e);
    }

    private Map<String, Object> build(String reason) {
        var cfg = plugin.getConfig();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("time", Instant.now().toString());
        m.put("reason", reason);
        m.put("pluginVersion", AwreReport.class.getPackage().getImplementationVersion());      // null when run from an unpacked build
        m.put("server", plugin.getServer().getVersion());
        m.put("java", Runtime.version().toString());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("streamEnabled", cfg.getBoolean("stream.enabled"));
        c.put("source", cfg.getString("stream.source"));
        c.put("radiusBlocks", cfg.getDouble("stream.radius"));
        c.put("maxHeldTiles", cfg.getInt("stream.max-held-tiles"));
        c.put("captureBudgetMicros", cfg.getInt("stream.capture-budget-micros"));
        c.put("storeFullPolicy", cfg.getString("stream.store-full-policy"));
        m.put("config", c);
        Runtime rt = Runtime.getRuntime();
        m.put("heapUsedMiB", (rt.totalMemory() - rt.freeMemory()) >> 20);
        m.put("heapMaxMiB", rt.maxMemory() >> 20);
        m.put("mspt", msptSection());
        m.put("counters", channel.counters().snapshot());
        m.put("capture", streaming.captureStats());
        var online = new ArrayList<Player>(plugin.getServer().getOnlinePlayers());
        m.put("playersOnline", online.size());
        m.put("awreSessions", channel.sessionCount());
        List<Object> players = new ArrayList<>();
        for (Player p : online) {
            if (players.size() >= MAX_PLAYERS) break;
            ServerSession s = channel.session(p);
            if (s == null) continue;                                                              // vanilla client: nothing AWRE to report
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getName());
            pm.put("state", s.state().name());
            pm.put("protocol", s.negotiatedVersion());
            pm.put("clientMod", s.clientModVersion());
            pm.put("tilesSent", s.tilesSent()); pm.put("acksOk", s.acksOk()); pm.put("acksInvalid", s.acksInvalid()); pm.put("acksStale", s.acksStale());
            pm.put("inFlight", s.inFlight()); pm.put("strikes", s.strikes()); pm.put("lastError", s.lastError());
            PlayerStreamer.Stats x = streaming.stats(p.getUniqueId());
            if (x != null) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("plannedLeaves", x.plannedLeaves()); sm.put("held", x.held()); sm.put("pending", x.pending()); sm.put("sent", x.sent());
                sm.put("wireBytes", x.wireBytes()); sm.put("dropped", x.dropped()); sm.put("holes", x.holes()); sm.put("stale", x.stale());
                sm.put("noData", x.noData()); sm.put("failures", x.failures()); sm.put("replans", x.replans());
                pm.put("stream", sm);
            }
            pm.put("terrainChunks", streaming.terrainChunks(p));
            players.add(pm);
        }
        m.put("players", players);
        return m;
    }

    /** The last MSPT window AwreMspt logged (milliseconds), or null with the reason when it is not running. */
    private Object msptSection() {
        if (mspt == null) return null;                                                            // set diagnostics.mspt-seconds > 0 to include it
        TimingWindow.Summary s = mspt.last();
        if (s == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ageSeconds", (System.currentTimeMillis() - mspt.lastAtMillis()) / 1000);
        m.put("ticks", s.total()); m.put("p50Ms", s.p50() / 1e6); m.put("p95Ms", s.p95() / 1e6); m.put("p99Ms", s.p99() / 1e6); m.put("maxMs", s.max() / 1e6);
        return m;
    }
}
