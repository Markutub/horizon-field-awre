package dev.horizonfield.paper;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable snapshot of config.yml with clamped, safe values. */
public record HorizonConfig(
        List<String> enabledWorlds,
        int captureQueueCapacity,
        int maxSnapshotsPerTick,
        double captureBudgetMillisPerTick,
        int analysisThreads,
        int analysisQueueCapacity,
        int pyramidMaxLevel,
        int flushIntervalSeconds,
        int sweepAzimuthBins,
        double sweepSlackBlocks,
        int sweepDefaultMaxRadius,
        int probeMaxPillars,
        int probeMaxSeconds) {

    public static HorizonConfig load(FileConfiguration c) {
        return new HorizonConfig(
                c.getStringList("worlds.enabled"),
                clamp(c.getInt("capture.queue-capacity", 4096), 64, 65536),
                clamp(c.getInt("capture.max-snapshots-per-tick", 2), 1, 32),
                Math.max(0.05, Math.min(10.0, c.getDouble("capture.budget-millis-per-tick", 0.5))),
                clamp(c.getInt("analysis.threads", 1), 1, 4),
                clamp(c.getInt("analysis.queue-capacity", 4096), 64, 65536),
                clamp(c.getInt("pyramid.max-level", 6), 0, 10),
                clamp(c.getInt("storage.flush-interval-seconds", 30), 5, 3600),
                clamp(c.getInt("sweep.azimuth-bins", 1024), 64, 8192),
                Math.max(0, c.getDouble("sweep.slack-blocks", 8.0)),
                clamp(c.getInt("sweep.default-max-radius-blocks", 512), 64, 8192),
                clamp(c.getInt("probe.max-pillars", 24), 4, 128),
                clamp(c.getInt("probe.max-seconds", 120), 5, 900));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    public boolean worldEnabled(String worldName) {
        return enabledWorlds.isEmpty() || enabledWorlds.contains(worldName);
    }
}
