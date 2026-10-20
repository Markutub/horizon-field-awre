package dev.horizonfield.paper;

import dev.horizonfield.core.ChunkView;
import org.bukkit.ChunkSnapshot;

/** Adapts a Bukkit {@link ChunkSnapshot} (an immutable copy, safe to read off the main thread) to the core's ChunkView. */
final class ChunkSnapshotView implements ChunkView {
    private final ChunkSnapshot snap;
    private final int minY;
    private final int maxY;

    ChunkSnapshotView(ChunkSnapshot snap, int minY, int maxY) {
        this.snap = snap;
        this.minY = minY;
        this.maxY = maxY;
    }

    @Override public int chunkX() { return snap.getX(); }
    @Override public int chunkZ() { return snap.getZ(); }
    @Override public int minY() { return minY; }

    @Override
    public int highestBlockY(int lx, int lz) {
        // The off-by-one semantics of ChunkSnapshot#getHighestBlockYAt (top block vs first free block) are not verified,
        // so normalise against the actual blocks: step down through air, then up while the block above is solid.
        int y = Math.max(minY, Math.min(maxY, snap.getHighestBlockYAt(lx, lz)));
        while (y >= minY && snap.getBlockType(lx, y, lz).isAir()) y--;
        if (y < minY) return minY - 1;
        while (y < maxY && !snap.getBlockType(lx, y + 1, lz).isAir()) y++;
        return y;
    }

    @Override
    public String blockKey(int lx, int y, int lz) {
        return snap.getBlockType(lx, y, lz).getKey().toString();
    }
}
