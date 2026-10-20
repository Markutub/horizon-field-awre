package dev.awre.protocol;

/** Every bound in one place. All values are validated/clamped; nothing here may grow with remote input. */
public record Limits(
        int maxFrameBytes,        // per frame, <= Awre.HARD_MAX_FRAME
        int maxDecodedTileBytes,  // per tile after inflate
        int maxLod,
        int maxTileCoord,         // |tileX|,|tileZ| bound
        int maxInFlightTiles,     // unacked tiles per player
        int helloBurst, double helloRefillPerSec,
        int tileBurst, double tileRefillPerSec,
        int strikeLimit,          // malformed frames before a session is blocked
        int clientTileCache,      // client-side tile store capacity
        int helloRetryTicks, int helloMaxTries) {

    public static Limits defaults() {
        return new Limits(16384, 65536, 8, 1_875_000, 4, 3, 0.1, 4, 2.0, 5, 64, 40, 5);
    }

    /** Same limits with a larger client tile cache (the default 64 is only enough for the G0 test tiles). */
    public Limits withClientTileCache(int n) {
        return new Limits(maxFrameBytes, maxDecodedTileBytes, maxLod, maxTileCoord, maxInFlightTiles, helloBurst, helloRefillPerSec,
                tileBurst, tileRefillPerSec, strikeLimit, n, helloRetryTicks, helloMaxTries);
    }

    /** Server-side streaming profile: a wider unacknowledged window and tile rate than the G0 defaults (ASSUMED values, see docs). */
    public Limits withStreaming(int inFlight, int tileBurst, double tileRefillPerSec) {
        return new Limits(maxFrameBytes, maxDecodedTileBytes, maxLod, maxTileCoord, inFlight, helloBurst, helloRefillPerSec,
                tileBurst, tileRefillPerSec, strikeLimit, clientTileCache, helloRetryTicks, helloMaxTries);
    }

    public Limits {
        if (maxFrameBytes < 256 || maxFrameBytes > Awre.HARD_MAX_FRAME) throw new IllegalArgumentException("maxFrameBytes");
        if (maxDecodedTileBytes < 16 || maxDecodedTileBytes > (1 << 20)) throw new IllegalArgumentException("maxDecodedTileBytes");
        if (maxLod < 0 || maxLod > 20) throw new IllegalArgumentException("maxLod");
        if (maxTileCoord < 1) throw new IllegalArgumentException("maxTileCoord");
        if (maxInFlightTiles < 1 || maxInFlightTiles > 64) throw new IllegalArgumentException("maxInFlightTiles");
        if (helloBurst < 1 || tileBurst < 1 || helloRefillPerSec <= 0 || tileRefillPerSec <= 0) throw new IllegalArgumentException("rates");
        if (strikeLimit < 1 || clientTileCache < 1 || helloRetryTicks < 1 || helloMaxTries < 1) throw new IllegalArgumentException("counts");
    }
}
