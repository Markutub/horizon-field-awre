package dev.horizonfield.core.terrain;

import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;

/**
 * 16x16 samples of top height and top material for one quadtree tile. Sample (x,z) of a level-L tile summarises a
 * 2^L x 2^L block square. top == {@link #NO_DATA} marks a sample with no observation (material is then 0).
 * Arrays are copied on the way in and out: instances are immutable.
 */
public final class LodTile {
    public static final int SIDE = 16;
    public static final int AREA = SIDE * SIDE;
    public static final short NO_DATA = Short.MIN_VALUE;

    private final LodKey key;
    private final short[] top, material;
    private final Confidence confidence;

    public LodTile(LodKey key, short[] top, short[] material, Confidence confidence) {
        if (top.length != AREA || material.length != AREA) throw new IllegalArgumentException("tile arrays must have 256 entries");
        this.key = key; this.top = top.clone(); this.material = material.clone(); this.confidence = confidence;
    }

    public LodKey key() { return key; }
    public Confidence confidence() { return confidence; }
    public int topAt(int x, int z) { return top[z * SIDE + x]; }
    public int materialAt(int x, int z) { return material[z * SIDE + x] & 0xFFFF; }
    public short[] topArray() { return top.clone(); }
    public short[] materialArray() { return material.clone(); }

    public int validSamples() {
        int n = 0;
        for (short t : top) if (t != NO_DATA) n++;
        return n;
    }

    public boolean sameContent(LodTile o) {
        return key.equals(o.key) && confidence == o.confidence && java.util.Arrays.equals(top, o.top) && java.util.Arrays.equals(material, o.material);
    }
}
