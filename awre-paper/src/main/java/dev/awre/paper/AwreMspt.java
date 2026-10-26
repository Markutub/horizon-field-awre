package dev.awre.paper;

import dev.horizonfield.core.TimingWindow;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Diagnostic: logs MSPT percentiles of the whole server so "with AWRE" can be compared with "without AWRE" on the same machine
 * (diagnostics.mspt-seconds; 0 = off, the default). It does not depend on stream.enabled, so a baseline run is the same jar with
 * stream.enabled=false. Every 100 ticks it reads the server's own record of the last 100 tick durations, which covers every tick
 * once (the timer is tick-aligned, so server lag does not create gaps). A tick time is the whole tick, including this plugin's work.
 * API-ASSUMPTION: org.bukkit.Server#getTickTimes() returns long[] nanoseconds (Paper API). Isolated in this class on purpose.
 */
final class AwreMspt {
    private final JavaPlugin plugin;
    private final int periodTicks;
    private TimingWindow window = new TimingWindow(8192);
    private int sinceLog;
    private BukkitTask task;

    AwreMspt(JavaPlugin plugin, int seconds) { this.plugin = plugin; this.periodTicks = Math.max(100, seconds * 20); }

    void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sample, 100L, 100L);
        plugin.getLogger().info("[AWRE] mspt diagnostics on: one line every " + periodTicks / 20 + " s (stream.enabled=" + plugin.getConfig().getBoolean("stream.enabled") + ")");
    }

    void stop() { if (task != null) task.cancel(); }

    private void sample() {
        for (long ns : plugin.getServer().getTickTimes()) window.record(ns);
        sinceLog += 100;
        if (sinceLog >= periodTicks) {
            sinceLog = 0;
            TimingWindow.Summary s = window.summary();
            window = new TimingWindow(8192);
            if (s.total() > 0) plugin.getLogger().info("[AWRE] mspt: " + s.format() + " stream=" + plugin.getConfig().getBoolean("stream.enabled"));
        }
    }
}
