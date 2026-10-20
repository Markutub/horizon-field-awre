package dev.horizonfield.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Sparse max/min height pyramid over chunk coordinates (cf. "maximum mipmaps", Tevs et al., I3D 2008).
 * Level 0 = one chunk, level L = 2^L x 2^L chunks. Writers are serialized; readers are lock-free and may observe
 * a briefly inconsistent parent chain, which is acceptable because consumers are conservative (see VisibilitySweep).
 */
public final class HeightPyramid {
    private final int maxLevel;
    private final ConcurrentHashMap<Long, PyramidNode>[] levels;
    private volatile int maxSeenY = Short.MIN_VALUE;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public HeightPyramid(int maxLevel) {
        if (maxLevel < 0 || maxLevel > 12) throw new IllegalArgumentException("maxLevel");
        this.maxLevel = maxLevel;
        this.levels = new ConcurrentHashMap[maxLevel + 1];
        for (int i = 0; i <= maxLevel; i++) levels[i] = new ConcurrentHashMap<>();
    }

    public static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    public int maxLevel() { return maxLevel; }
    /** Monotonic upper bound of every surface Y ever inserted (not lowered on removal). Used for early-out. */
    public int maxSeenY() { return maxSeenY; }
    public int size(int level) { return levels[level].size(); }

    public PyramidNode get(int level, int x, int z) { return levels[level].get(key(x, z)); }

    public synchronized void put(ColumnTile t) {
        levels[0].put(key(t.chunkX(), t.chunkZ()),
                new PyramidNode((short) t.minTop(), (short) t.maxTop(), (short) t.dominantMaterial(), t.confidence(), true));
        if (t.maxTop() > maxSeenY) maxSeenY = t.maxTop();
        propagate(t.chunkX(), t.chunkZ());
    }

    public synchronized void remove(int chunkX, int chunkZ) {
        levels[0].remove(key(chunkX, chunkZ));
        propagate(chunkX, chunkZ);
    }

    private void propagate(int cx, int cz) {
        int x = cx, z = cz;
        for (int level = 1; level <= maxLevel; level++) {
            x = Math.floorDiv(x, 2);
            z = Math.floorDiv(z, 2);
            short mn = Short.MAX_VALUE, mx = Short.MIN_VALUE, mat = 0;
            Confidence conf = Confidence.OBSERVED;
            int found = 0;
            boolean allComplete = true;
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    PyramidNode c = levels[level - 1].get(key(x * 2 + dx, z * 2 + dz));
                    if (c == null) { allComplete = false; continue; }
                    found++;
                    if (!c.complete()) allComplete = false;
                    if (c.minY() < mn) mn = c.minY();
                    if (c.maxY() > mx) { mx = c.maxY(); mat = c.material(); }
                    conf = Confidence.weakest(conf, c.confidence());
                }
            }
            long k = key(x, z);
            if (found == 0) levels[level].remove(k);
            else levels[level].put(k, new PyramidNode(mn, mx, mat, conf, allComplete));
        }
    }
}
