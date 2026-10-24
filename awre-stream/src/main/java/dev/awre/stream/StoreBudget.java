package dev.awre.stream;

import dev.horizonfield.core.terrain.TerrainStore;

/** Turns a memory budget into the observed-chunk cap of a {@link TerrainStore}. Pure arithmetic so it is testable without a server. */
public final class StoreBudget {
    /** Auto budget: an eighth of the max heap, but at most this much (512 MiB is about 1.2 million chunks, a ~17 000-block square). */
    public static final long AUTO_CAP_BYTES = 512L * 1024 * 1024;
    public static final int MAX_CONFIGURED_MIB = 65_536;
    private static final long MIN_CHUNKS = 1024;

    private StoreBudget() { }

    /** A resolved cap and where it came from, for the log. */
    public record Resolved(int maxChunks, long budgetBytes, boolean auto) { }

    /**
     * @param configuredMib {@code stream.max-store-mib}: 0 = automatic, otherwise that many MiB
     * @param maxHeapBytes  {@code Runtime.maxMemory()}
     * @throws IllegalArgumentException if configuredMib is negative or above {@link #MAX_CONFIGURED_MIB}
     */
    public static Resolved resolve(int configuredMib, long maxHeapBytes) {
        if (configuredMib < 0 || configuredMib > MAX_CONFIGURED_MIB) throw new IllegalArgumentException("stream.max-store-mib must be 0.." + MAX_CONFIGURED_MIB);
        boolean auto = configuredMib == 0;
        long bytes = auto ? Math.min(AUTO_CAP_BYTES, Math.max(0, maxHeapBytes) / 8) : configuredMib * 1024L * 1024L;
        long chunks = Math.max(MIN_CHUNKS, bytes / TerrainStore.BYTES_PER_CHUNK_ESTIMATE);
        return new Resolved((int) Math.min(Integer.MAX_VALUE, chunks), bytes, auto);
    }
}
