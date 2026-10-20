package dev.horizonfield.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable 16x16 summary of one chunk: top surface Y and top material per column.
 * This is the minimum useful observation. Caves/overhangs are intentionally NOT represented yet (see docs/ARCHITECTURE.md).
 */
public final class ColumnTile {
    public static final int SIZE = 16;
    public static final int AREA = SIZE * SIZE;

    private final int chunkX;
    private final int chunkZ;
    private final short[] top;
    private final short[] material;
    private final Confidence confidence;
    private final long observedAtMillis;
    private final short minTop;
    private final short maxTop;
    private final short dominantMaterial;

    public ColumnTile(int chunkX, int chunkZ, short[] top, short[] material, Confidence confidence, long observedAtMillis) {
        if (top.length != AREA || material.length != AREA) throw new IllegalArgumentException("tile arrays must have 256 entries");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.top = top.clone();
        this.material = material.clone();
        this.confidence = confidence;
        this.observedAtMillis = observedAtMillis;
        short mn = Short.MAX_VALUE, mx = Short.MIN_VALUE;
        Map<Short, Integer> counts = new HashMap<>();
        short best = 0;
        int bestCount = -1;
        for (int i = 0; i < AREA; i++) {
            if (top[i] < mn) mn = top[i];
            if (top[i] > mx) mx = top[i];
            int c = counts.merge(material[i], 1, Integer::sum);
            if (c > bestCount) { bestCount = c; best = material[i]; }
        }
        this.minTop = mn;
        this.maxTop = mx;
        this.dominantMaterial = best;
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int topAt(int lx, int lz) { return top[lz * SIZE + lx]; }
    public int materialAt(int lx, int lz) { return material[lz * SIZE + lx]; }
    public int minTop() { return minTop; }
    public int maxTop() { return maxTop; }
    public int dominantMaterial() { return dominantMaterial; }
    public Confidence confidence() { return confidence; }
    public long observedAtMillis() { return observedAtMillis; }
    public short[] topArray() { return top.clone(); }
    public short[] materialArray() { return material.clone(); }

    /** 64-bit FNV-1a over coordinates-independent content (top, material, confidence). observedAtMillis is excluded on purpose. */
    public long contentHash64() {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < AREA; i++) {
            h = (h ^ (top[i] & 0xFFFF)) * 0x100000001b3L;
            h = (h ^ (material[i] & 0xFFFF)) * 0x100000001b3L;
        }
        return (h ^ confidence.ordinal()) * 0x100000001b3L;
    }

    public boolean sameContent(ColumnTile o) {
        return chunkX == o.chunkX && chunkZ == o.chunkZ && java.util.Arrays.equals(top, o.top)
                && java.util.Arrays.equals(material, o.material) && confidence == o.confidence;
    }
}
