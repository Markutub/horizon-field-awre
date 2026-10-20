package dev.horizonfield.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents the append-only TileLog from growing without bound: a chunk that is re-observed with identical content
 * (every ChunkLoadEvent re-captures it) must not be appended again. Memory: one long per known chunk.
 * A 64-bit hash collision would skip persisting a genuine change with probability ~2^-64 per change (accepted).
 */
public final class TileDeduper {
    private final ConcurrentHashMap<Long, Long> lastHash = new ConcurrentHashMap<>();

    /** Call while replaying a log so previously persisted tiles are not re-appended. */
    public void seed(ColumnTile t) { lastHash.put(HeightPyramid.key(t.chunkX(), t.chunkZ()), t.contentHash64()); }

    /** True if the tile differs from the last persisted/seeded one for its chunk (and records it as persisted). */
    public boolean shouldPersist(ColumnTile t) {
        long k = HeightPyramid.key(t.chunkX(), t.chunkZ());
        long h = t.contentHash64();
        Long prev = lastHash.put(k, h);
        return prev == null || prev != h;
    }

    public int size() { return lastHash.size(); }
}
