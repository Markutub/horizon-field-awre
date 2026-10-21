package dev.horizonfield.core.terrain;

import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import java.io.IOException;

/**
 * Wire payload of one terrain tile (the "decoded" bytes inside an AWRE TILE message; AWRE deflates and CRCs them).
 * <pre> u8 format(=1) | u8 confidence ordinal | 256 x i16 top (big-endian, row-major z*16+x) | 256 x u16 material </pre>
 * Total {@value #BYTES} bytes. The tile's key is NOT in the payload: it is already in the TILE header.
 * <p>FORMAT 2 (compact, {@link #encodeV2}): u8 2 | u8 confidence | i16 minTop | u8 topBits(0..15) | u8 flags(bit0: some samples
 * have no data) | [32-byte MSB-first validity bitmap if bit0] | topBits-wide (top-minTop) per VALID sample | u16 paletteCount |
 * u16 material id x paletteCount (first-appearance order) | u8 matBits | matBits-wide local index per VALID sample. Bits are packed
 * MSB first, the last byte zero padded; the payload must end exactly there. {@link #decode} accepts both formats; senders must
 * only use v2 towards peers that negotiated it (a v1-only decoder rejects it).
 */
public final class LodTileCodec {
    public static final int FORMAT = 1;
    public static final int BYTES = 2 + LodTile.AREA * 4;

    private LodTileCodec() { }

    public static final int FORMAT_V2 = 2;

    public static byte[] encode(LodTile t) {
        byte[] b = new byte[BYTES];
        b[0] = FORMAT; b[1] = (byte) t.confidence().ordinal();
        int p = 2;
        for (int i = 0; i < LodTile.AREA; i++) { int v = t.topAt(i % 16, i / 16); b[p++] = (byte) (v >>> 8); b[p++] = (byte) v; }
        for (int i = 0; i < LodTile.AREA; i++) { int v = t.materialAt(i % 16, i / 16); b[p++] = (byte) (v >>> 8); b[p++] = (byte) v; }
        return b;
    }

    /** @throws IOException on any malformed payload (wrong length, unknown format or confidence, material with NO_DATA top). */
    public static LodTile decode(LodKey key, byte[] b) throws IOException {
        if (b.length >= 1 && b[0] == FORMAT_V2) return decodeV2(key, b);
        if (b.length != BYTES) throw new IOException("terrain payload length " + b.length + " != " + BYTES);
        if (b[0] != FORMAT) throw new IOException("terrain payload format " + b[0]);
        int c = b[1] & 0xFF;
        if (c >= Confidence.values().length) throw new IOException("confidence " + c);
        short[] top = new short[LodTile.AREA], mat = new short[LodTile.AREA];
        int p = 2;
        for (int i = 0; i < LodTile.AREA; i++, p += 2) top[i] = (short) (((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF));
        for (int i = 0; i < LodTile.AREA; i++, p += 2) mat[i] = (short) (((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF));
        for (int i = 0; i < LodTile.AREA; i++) if (top[i] == LodTile.NO_DATA && mat[i] != 0) throw new IOException("material on a NO_DATA sample");
        return new LodTile(key, top, mat, Confidence.values()[c]);
    }

    private static int bitsFor(int maxValue) { return maxValue <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(maxValue); }

    /** Compact encoding (format 2). Deterministic: the same tile always yields the same bytes. */
    public static byte[] encodeV2(LodTile t) {
        int valid = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        boolean anyMissing = false;
        for (int i = 0; i < LodTile.AREA; i++) {
            int v = t.topAt(i % 16, i / 16);
            if (v == LodTile.NO_DATA) { anyMissing = true; continue; }
            valid++;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (valid == 0) { min = 0; max = 0; }
        int topBits = bitsFor(max - min);
        short[] pal = new short[LodTile.AREA];
        int n = 0;
        int[] local = new int[LodTile.AREA];
        for (int i = 0; i < LodTile.AREA; i++) {
            if (t.topAt(i % 16, i / 16) == LodTile.NO_DATA) continue;
            short m = (short) t.materialAt(i % 16, i / 16);
            int k = 0;
            while (k < n && pal[k] != m) k++;
            if (k == n) pal[n++] = m;
            local[i] = k;
        }
        int matBits = bitsFor(n - 1);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(64 + valid * 2);
        bo.write(FORMAT_V2); bo.write(t.confidence().ordinal());
        bo.write(min >>> 8); bo.write(min);
        bo.write(topBits); bo.write(anyMissing ? 1 : 0);
        if (anyMissing) {
            byte[] bm = new byte[LodTile.AREA / 8];
            for (int i = 0; i < LodTile.AREA; i++) if (t.topAt(i % 16, i / 16) != LodTile.NO_DATA) bm[i >> 3] |= (byte) (0x80 >>> (i & 7));
            bo.write(bm, 0, bm.length);
        }
        BitWriter w = new BitWriter(bo);
        if (topBits > 0) for (int i = 0; i < LodTile.AREA; i++) { int v = t.topAt(i % 16, i / 16); if (v != LodTile.NO_DATA) w.put(v - min, topBits); }
        w.finish();
        bo.write(n >>> 8); bo.write(n);
        for (int k = 0; k < n; k++) { bo.write(pal[k] >>> 8); bo.write(pal[k]); }
        bo.write(matBits);
        w = new BitWriter(bo);
        if (matBits > 0) for (int i = 0; i < LodTile.AREA; i++) if (t.topAt(i % 16, i / 16) != LodTile.NO_DATA) w.put(local[i], matBits);
        w.finish();
        return bo.toByteArray();
    }

    private static LodTile decodeV2(LodKey key, byte[] b) throws IOException {
        if (b.length < 7) throw new IOException("v2 payload too short: " + b.length);
        int c = b[1] & 0xFF;
        if (c >= Confidence.values().length) throw new IOException("confidence " + c);
        int min = (short) (((b[2] & 0xFF) << 8) | (b[3] & 0xFF));
        int topBits = b[4] & 0xFF, flags = b[5] & 0xFF;
        if (topBits > 15) throw new IOException("topBits " + topBits);
        if ((flags & ~1) != 0) throw new IOException("unknown flags " + flags);
        int p = 6;
        boolean[] valid = new boolean[LodTile.AREA];
        int nValid = LodTile.AREA;
        java.util.Arrays.fill(valid, true);
        if ((flags & 1) != 0) {
            if (b.length < p + LodTile.AREA / 8) throw new IOException("truncated validity bitmap");
            nValid = 0;
            for (int i = 0; i < LodTile.AREA; i++) { valid[i] = (b[p + (i >> 3)] & (0x80 >>> (i & 7))) != 0; if (valid[i]) nValid++; }
            p += LodTile.AREA / 8;
            if (nValid == LodTile.AREA) throw new IOException("bitmap flag set but every sample is valid");
        }
        if (nValid > 0 && min == LodTile.NO_DATA) throw new IOException("minTop collides with NO_DATA");
        if (nValid == 0 && (min != 0 || topBits != 0)) throw new IOException("empty tile with height data");
        BitReader r = new BitReader(b, p);
        int[] top = new int[LodTile.AREA];
        for (int i = 0; i < LodTile.AREA; i++) {
            if (!valid[i]) { top[i] = LodTile.NO_DATA; continue; }
            int v = min + (topBits > 0 ? r.get(topBits) : 0);
            if (v > Short.MAX_VALUE || v == LodTile.NO_DATA) throw new IOException("height out of range");
            top[i] = v;
        }
        p = r.endOffset();
        if (b.length < p + 3) throw new IOException("truncated palette");
        int n = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
        p += 2;
        if (n > nValid || (nValid > 0 && n == 0)) throw new IOException("palette count " + n + " for " + nValid + " valid samples");
        if (b.length < p + 2 * n + 1) throw new IOException("truncated palette entries");
        short[] pal = new short[n];
        for (int k = 0; k < n; k++, p += 2) pal[k] = (short) (((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF));
        int matBits = b[p++] & 0xFF;
        if (matBits != bitsFor(n - 1)) throw new IOException("matBits " + matBits + " does not fit palette of " + n);
        r = new BitReader(b, p);
        short[] topS = new short[LodTile.AREA], mat = new short[LodTile.AREA];
        for (int i = 0; i < LodTile.AREA; i++) {
            topS[i] = (short) top[i];
            if (!valid[i]) continue;
            int idx = matBits > 0 ? r.get(matBits) : 0;
            if (idx >= n) throw new IOException("palette index " + idx + " >= " + n);
            mat[i] = pal[idx];
        }
        if (r.endOffset() != b.length) throw new IOException("trailing bytes in v2 payload");
        return new LodTile(key, topS, mat, Confidence.values()[c]);
    }

    private static final class BitWriter {
        private final java.io.ByteArrayOutputStream out;
        private int acc, nbits;
        BitWriter(java.io.ByteArrayOutputStream out) { this.out = out; }
        void put(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                acc = (acc << 1) | ((value >>> i) & 1);
                if (++nbits == 8) { out.write(acc); acc = 0; nbits = 0; }
            }
        }
        void finish() { if (nbits > 0) { out.write(acc << (8 - nbits)); acc = 0; nbits = 0; } }
    }

    private static final class BitReader {
        private final byte[] b;
        private int pos, bit;       // pos = next byte, bit = bits already consumed of b[pos]
        private final int start;
        BitReader(byte[] b, int pos) { this.b = b; this.pos = pos; this.start = pos; }
        int get(int bits) throws IOException {
            int v = 0;
            for (int i = 0; i < bits; i++) {
                if (pos >= b.length) throw new IOException("truncated packed data");
                v = (v << 1) | ((b[pos] >>> (7 - bit)) & 1);
                if (++bit == 8) { bit = 0; pos++; }
            }
            return v;
        }
        /** Offset after the packed run; rejects non-zero padding bits so one tile has exactly one encoding. */
        int endOffset() throws IOException {
            if (bit > 0) {
                if ((b[pos] & (0xFF >>> bit)) != 0) throw new IOException("non-zero padding bits");
                return pos + 1;
            }
            return pos;
        }
    }
}
