package dev.horizonfield.core;

/** Minimal read-only view of one loaded chunk. Implemented over a Bukkit ChunkSnapshot in the paper module. */
public interface ChunkView {
    int chunkX();
    int chunkZ();
    int minY();
    /** Y of the highest non-air block in the column, or {@code minY() - 1} when the column is empty. */
    int highestBlockY(int localX, int localZ);
    /** Namespaced key of the block at the given local position. */
    String blockKey(int localX, int y, int localZ);
    /** Namespaced biome key at the position (e.g. "minecraft:plains"), or null if the view cannot tell. Used only to tint grass, foliage and water. */
    default String biomeKey(int localX, int y, int localZ) { return null; }
}
