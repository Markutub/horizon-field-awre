package dev.awre.paper;

import dev.awre.protocol.Limits;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable, validated configuration. Anything invalid falls back to {@link Limits#defaults()} (logged). */
public record AwreConfig(boolean enabled, boolean autoSendTestTile, boolean debug, Limits limits, int autoFragmentTestSide,
                         boolean autoStream, boolean streamCaptured, boolean streamPersist, boolean streamImport, int syntheticChunkRadius, double streamRadius, double streamBytesPerSecond, int streamMaxHeldTiles,
                         double compactGarbageRatio, long compactMinBytes, int revalidateSeconds, int captureBudgetMicros, boolean skipCeilingWorlds) {

    public static AwreConfig load(FileConfiguration c, Logger log) {
        Limits d = Limits.defaults();
        Limits l;
        try {
            l = new Limits(
                    c.getInt("limits.max-frame-bytes", d.maxFrameBytes()),
                    c.getInt("limits.max-decoded-tile-bytes", d.maxDecodedTileBytes()),
                    c.getInt("limits.max-lod", d.maxLod()),
                    d.maxTileCoord(),
                    c.getInt("limits.max-in-flight-tiles", d.maxInFlightTiles()),
                    c.getInt("limits.hello-burst", d.helloBurst()),
                    c.getDouble("limits.hello-refill-per-sec", d.helloRefillPerSec()),
                    c.getInt("limits.tile-burst", d.tileBurst()),
                    c.getDouble("limits.tile-refill-per-sec", d.tileRefillPerSec()),
                    c.getInt("limits.strike-limit", d.strikeLimit()),
                    d.clientTileCache(), d.helloRetryTicks(), d.helloMaxTries());
        } catch (IllegalArgumentException e) {
            log.warning("[AWRE] invalid limits in config.yml (" + e.getMessage() + "); using safe defaults");
            l = d;
        }
        int side = c.getInt("auto-fragment-test-side", 0);
        if (side != 0 && (side < 16 || side > 256)) { log.warning("[AWRE] auto-fragment-test-side must be 0 or 16..256; disabled"); side = 0; }
        if (!c.isConfigurationSection("stream")) log.warning("config.yml has no 'stream:' section with settings under it (an empty or unindented 'stream:' block reads as empty): every stream setting is at its default, including stream.enabled=false. Indent the stream settings by two spaces under 'stream:'.");
        boolean stream = c.getBoolean("stream.enabled", false);
        String source = c.getString("stream.source", "synthetic");
        boolean captured = "captured".equalsIgnoreCase(source);
        if (!captured && !"synthetic".equalsIgnoreCase(source)) log.warning("[AWRE] stream.source must be 'synthetic' or 'captured'; using synthetic");
        int synth = c.getInt("stream.synthetic-chunk-radius", 0);
        if (synth < 0 || synth > 99) { log.warning("[AWRE] stream.synthetic-chunk-radius must be 0..99; using 0"); synth = 0; }
        double radius = c.getDouble("stream.radius", 1000);
        if (radius < 256 || radius > 8000) { log.warning("[AWRE] stream.radius must be 256..8000; using 1000"); radius = 1000; }
        if (radius > 2000) log.warning("[AWRE] stream.radius " + radius + " exceeds the 2048-block projection far plane measured on 1.21.11 (client render distance 4 and 12): tiles beyond it are expected to be clipped by the GPU yet still cost bandwidth and memory (not yet confirmed in a frame)");
        double bps = c.getDouble("stream.bytes-per-second", 250_000);
        if (bps < 10_000 || bps > 50_000_000) { log.warning("[AWRE] stream.bytes-per-second out of range; using 250000"); bps = 250_000; }
        int held = c.getInt("stream.max-held-tiles", 4096);
        if (held < 64 || held > dev.awre.stream.StreamConfig.CLIENT_MESH_CAP) { log.warning("[AWRE] stream.max-held-tiles must be 64.." + dev.awre.stream.StreamConfig.CLIENT_MESH_CAP + " (the client meshes at most that many tiles); using 4096"); held = 4096; }
        double ratio = c.getDouble("stream.compact-garbage-ratio", 0.5);
        if (ratio < 0.1 || ratio > 0.95) { log.warning("[AWRE] stream.compact-garbage-ratio must be 0.1..0.95; using 0.5"); ratio = 0.5; }
        long minBytes = c.getLong("stream.compact-min-bytes", 1_048_576L);
        if (minBytes < 0 || minBytes > (1L << 40)) { log.warning("[AWRE] stream.compact-min-bytes out of range; using 1048576"); minBytes = 1_048_576L; }
        int reval = c.getInt("stream.revalidate-seconds", 600);
        if (reval < 0 || reval > 86_400) { log.warning("[AWRE] stream.revalidate-seconds must be 0..86400; using 600"); reval = 600; }
        int budget = c.getInt("stream.capture-budget-micros", 500);
        if (budget < 100 || budget > 5000) { log.warning("[AWRE] stream.capture-budget-micros must be 100..5000; using 500"); budget = 500; }
        for (var f : dev.awre.stream.StreamConfigCheck.findings(stream, captured, synth)) {      // say plainly what this stream setup will actually do
            if (f.level() == dev.awre.stream.StreamConfigCheck.Level.WARN) log.warning(f.text()); else log.info(f.text());      // the plugin logger already adds the [AWRE] prefix
        }
        return new AwreConfig(c.getBoolean("enabled", true), c.getBoolean("auto-send-test-tile", true), c.getBoolean("debug", false), l, side,
                stream, captured, c.getBoolean("stream.persist", true), c.getBoolean("stream.import-horizon-cache", true), synth, radius, bps, held, ratio, minBytes, reval, budget, c.getBoolean("stream.skip-ceiling-worlds", true));
    }
}
