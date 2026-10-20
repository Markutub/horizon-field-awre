package dev.horizonfield.core.lod;

/** Quadtree node / tile identity: level 0 is finest. Tile (lod, tx, tz) covers [tx*S*2^lod, (tx+1)*S*2^lod) blocks in X and likewise Z. */
public record LodKey(int lod, int tx, int tz) {
    public LodKey parent() { return new LodKey(lod + 1, Math.floorDiv(tx, 2), Math.floorDiv(tz, 2)); }

    public LodKey[] children() {
        if (lod == 0) throw new IllegalStateException("level 0 has no children");
        int x = tx * 2, z = tz * 2, l = lod - 1;
        return new LodKey[]{new LodKey(l, x, z), new LodKey(l, x + 1, z), new LodKey(l, x, z + 1), new LodKey(l, x + 1, z + 1)};
    }

    /** True if other lies inside this node's footprint (or equals it). */
    public boolean contains(LodKey o) {
        if (o.lod > lod) return false;
        int shift = lod - o.lod;
        return (o.tx >> shift) == tx && (o.tz >> shift) == tz;
    }
}
