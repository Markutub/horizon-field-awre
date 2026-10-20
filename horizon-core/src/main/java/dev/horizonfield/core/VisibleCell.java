package dev.horizonfield.core;

/** A pyramid node (level, cx, cz in that level's coordinates) that is visible from the viewer, with its visible Y span. */
public record VisibleCell(int level, int cx, int cz, int yLow, int yHigh, int material, Confidence confidence) {
    public int sizeBlocks() { return 16 << level; }
    public int x0() { return cx * sizeBlocks(); }
    public int z0() { return cz * sizeBlocks(); }
}
