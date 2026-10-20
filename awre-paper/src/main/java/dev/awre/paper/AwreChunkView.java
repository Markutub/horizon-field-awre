package dev.awre.paper;

import dev.horizonfield.core.ChunkView;
import org.bukkit.ChunkSnapshot;

/** Adapts an immutable Bukkit {@link ChunkSnapshot} (safe off the main thread) to the core ChunkView. Same logic as horizon-paper's adapter. */
final class AwreChunkView implements ChunkView {
    private final ChunkSnapshot snap;
    private final int minY, maxY;

    AwreChunkView(ChunkSnapshot snap, int minY, int maxY) { this.snap = snap; this.minY = minY; this.maxY = maxY; }

    @Override public int chunkX() { return snap.getX(); }
    @Override public int chunkZ() { return snap.getZ(); }
    @Override public int minY() { return minY; }

    @Override public int highestBlockY(int lx, int lz) {
        // normalise against the real blocks: the exact semantics of getHighestBlockYAt (top vs first free block) are not verified
        int y = Math.max(minY, Math.min(maxY, snap.getHighestBlockYAt(lx, lz)));
        while (y >= minY && snap.getBlockType(lx, y, lz).isAir()) y--;
        if (y < minY) return minY - 1;
        while (y < maxY && !snap.getBlockType(lx, y + 1, lz).isAir()) y++;
        return y;
    }

    @Override public String blockKey(int lx, int y, int lz) { return snap.getBlockType(lx, y, lz).getKey().toString(); }
}
