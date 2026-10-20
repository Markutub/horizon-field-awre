package dev.awre.stream;

/**
 * Per-player streaming parameters. Every default is an ASSUMPTION to be measured: bytesPerSecond (about 2 Mbit/s of tile
 * traffic), rttSeconds (only sizes the in-flight window via Little's law), maxHeldTiles (must not exceed the client cache).
 */
public record StreamConfig(double radius, double bytesPerSecond, long burstBytes, double rttSeconds, int maxHeldTiles,
                           double replanBlocks, int maxDropPerTick, int retryLimit) {

    public static StreamConfig defaults() { return new StreamConfig(2000, 250_000, 65_536, 0.1, 4096, 16, 256, 3); }

    public StreamConfig {
        if (radius < 64 || radius > 16000) throw new IllegalArgumentException("radius");
        if (bytesPerSecond < 1000 || burstBytes < 1024 || rttSeconds < 0 || rttSeconds > 5) throw new IllegalArgumentException("budget");
        if (maxHeldTiles < 8 || maxHeldTiles > 100_000 || replanBlocks < 1 || maxDropPerTick < 1 || retryLimit < 1) throw new IllegalArgumentException("limits");
    }
}
