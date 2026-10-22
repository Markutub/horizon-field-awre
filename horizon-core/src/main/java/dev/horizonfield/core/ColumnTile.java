package dev.horizonfield.core;

import java.util.Arrays;

/**
 * Immutable 16x16 summary of one chunk: top surface Y and top material per column.
 * This is the minimum useful observation. Caves/overhangs are intentionally NOT represented yet (see docs/ARCHITECTURE.md).
 *
 * <p>Storage is PACKED, not two short[256]: the server keeps one of these per observed chunk for the lifetime of the process, so
 * the in-heap size is what bounds how much world a server can remember. Heights are stored as (top - minTop) with the fewest bits
 * that fit (terrain inside one chunk rarely spans more than a few dozen blocks), materials as indices into a per-chunk palette
 * of the distinct ids (a chunk rarely has more than a handful). The packing is canonical (same content, same bytes), which is what
 * makes {@link #sameContent} a plain array comparison. The public API is unchanged; {@link #topArray()} and {@link #materialArray()}
 * unpack into fresh arrays.
 */
public final class ColumnTile {
    public static final int SIZE = 16;
    public static final int AREA = SIZE * SIZE;

    private final int chunkX;
    private final int chunkZ;
    private final byte[] topPacked;       // AREA values of topWidth bits (top - minTop), MSB first
    private final int topWidth;           // 0..16
    private final short[] palette;        // distinct material ids in order of first appearance (1..AREA entries)
    private final byte[] matPacked;       // AREA indices into palette of matWidth bits, MSB first
    private final int matWidth;           // 0..8
    private final Confidence confidence;
    private final long observedAtMillis;
    private final short minTop;
    private final short maxTop;
    private final short dominantMaterial;

    public ColumnTile(int chunkX, int chunkZ, short[] top, short[] material, Confidence confidence, long observedAtMillis) {
        if (top.length != AREA || material.length != AREA) throw new IllegalArgumentException("tile arrays must have 256 entries");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.confidence = confidence;
        this.observedAtMillis = observedAtMillis;
        short mn = Short.MAX_VALUE, mx = Short.MIN_VALUE;
        for (int i = 0; i < AREA; i++) { if (top[i] < mn) mn = top[i]; if (top[i] > mx) mx = top[i]; }
        this.minTop = mn;
        this.maxTop = mx;
        this.topWidth = bitsFor(mx - mn);
        int[] deltas = new int[AREA];
        for (int i = 0; i < AREA; i++) deltas[i] = top[i] - mn;
        this.topPacked = pack(deltas, topWidth);

        short[] pal = new short[AREA];
        int[] counts = new int[AREA];
        int[] idx = new int[AREA];
        int n = 0;
        short best = 0;
        int bestCount = -1;
        for (int i = 0; i < AREA; i++) {
            short m = material[i];
            int k = 0;
            while (k < n && pal[k] != m) k++;
            if (k == n) pal[n++] = m;
            idx[i] = k;
            int c = ++counts[k];
            if (c > bestCount) { bestCount = c; best = m; }      // same tie-break as the original: first material to reach the highest count
        }
        this.dominantMaterial = best;
        this.palette = Arrays.copyOf(pal, n);
        this.matWidth = bitsFor(n - 1);
        this.matPacked = pack(idx, matWidth);
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int topAt(int lx, int lz) { return minTop + read(topPacked, lz * SIZE + lx, topWidth); }
    public int materialAt(int lx, int lz) { return palette[read(matPacked, lz * SIZE + lx, matWidth)]; }
    public int minTop() { return minTop; }
    public int maxTop() { return maxTop; }
    public int dominantMaterial() { return dominantMaterial; }
    public Confidence confidence() { return confidence; }
    public long observedAtMillis() { return observedAtMillis; }

    public short[] topArray() {
        short[] a = new short[AREA];
        for (int i = 0; i < AREA; i++) a[i] = (short) (minTop + read(topPacked, i, topWidth));
        return a;
    }

    public short[] materialArray() {
        short[] a = new short[AREA];
        for (int i = 0; i < AREA; i++) a[i] = palette[read(matPacked, i, matWidth)];
        return a;
    }

    /** Approximate heap footprint of this object (header, fields, the three arrays; 8-byte aligned, compressed oops). For instrumentation, not accounting. */
    public int approxHeapBytes() {
        return 72 + align(16 + topPacked.length) + align(16 + 2 * palette.length) + align(16 + matPacked.length);
    }

    private static int align(int n) { return (n + 7) & ~7; }

    /** 64-bit FNV-1a over coordinates-independent content (top, material, confidence). observedAtMillis is excluded on purpose. Same values as before the packing. */
    public long contentHash64() {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < AREA; i++) {
            h = (h ^ ((short) (minTop + read(topPacked, i, topWidth)) & 0xFFFF)) * 0x100000001b3L;
            h = (h ^ (palette[read(matPacked, i, matWidth)] & 0xFFFF)) * 0x100000001b3L;
        }
        return (h ^ confidence.ordinal()) * 0x100000001b3L;
    }

    public boolean sameContent(ColumnTile o) {
        return chunkX == o.chunkX && chunkZ == o.chunkZ && confidence == o.confidence
                && minTop == o.minTop && topWidth == o.topWidth && matWidth == o.matWidth
                && Arrays.equals(topPacked, o.topPacked) && Arrays.equals(palette, o.palette) && Arrays.equals(matPacked, o.matPacked);
    }

    private static int bitsFor(int maxValue) { return maxValue <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(maxValue); }

    private static byte[] pack(int[] values, int width) {
        byte[] out = new byte[(values.length * width + 7) >>> 3];
        if (width == 0) return out;
        int bit = 0;
        for (int v : values) {
            for (int b = width - 1; b >= 0; b--, bit++) if (((v >>> b) & 1) != 0) out[bit >>> 3] |= (byte) (0x80 >>> (bit & 7));
        }
        return out;
    }

    /** Reads value number {@code i} of {@code width} bits (width <= 16: it always fits in the 3 bytes gathered here). */
    private static int read(byte[] d, int i, int width) {
        if (width == 0) return 0;
        int bit = i * width, at = bit >>> 3, shift = bit & 7;
        int v = (d[at] & 0xFF) << 16;
        if (at + 1 < d.length) v |= (d[at + 1] & 0xFF) << 8;
        if (at + 2 < d.length) v |= d[at + 2] & 0xFF;
        return (v >>> (24 - shift - width)) & ((1 << width) - 1);
    }
}
