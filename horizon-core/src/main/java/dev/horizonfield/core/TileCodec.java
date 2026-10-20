package dev.horizonfield.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Experimental compact tile encoding (NOT yet wired into TileLog; format v1 there is unchanged).
 *
 * Layout (big endian):
 *   int chunkX | int chunkZ | byte confidence | long observedAtMillis
 *   short minTop | byte topBits | packed (top - minTop) x256        (topBits 0 => flat, nothing packed)
 *   short paletteCount | short globalId x paletteCount | byte matBits | packed local index x256
 *
 * Techniques: per-tile local palette + fixed-width bit packing + base/delta heights. No Deflate here; callers may
 * deflate a whole batch of tiles instead (see tools/bench/CodecBench.java for measured numbers).
 */
public final class TileCodec {
    private TileCodec() { }

    public static byte[] encode(ColumnTile t) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(160);
            DataOutputStream d = new DataOutputStream(bo);
            d.writeInt(t.chunkX());
            d.writeInt(t.chunkZ());
            d.writeByte(t.confidence().ordinal());
            d.writeLong(t.observedAtMillis());

            int min = t.minTop();
            int range = t.maxTop() - min;
            int topBits = bitsFor(range);
            d.writeShort(min);
            d.writeByte(topBits);
            if (topBits > 0) {
                int[] v = new int[ColumnTile.AREA];
                for (int i = 0; i < v.length; i++) v[i] = t.topAt(i & 15, i >> 4) - min;
                pack(d, v, topBits);
            }

            short[] mats = t.materialArray();
            short[] palette = new short[ColumnTile.AREA];
            int n = 0;
            int[] local = new int[ColumnTile.AREA];
            for (int i = 0; i < mats.length; i++) {
                int idx = -1;
                for (int k = 0; k < n; k++) if (palette[k] == mats[i]) { idx = k; break; }
                if (idx < 0) { palette[n] = mats[i]; idx = n++; }
                local[i] = idx;
            }
            d.writeShort(n);
            for (int k = 0; k < n; k++) d.writeShort(palette[k]);
            int matBits = bitsFor(n - 1);
            d.writeByte(matBits);
            if (matBits > 0) pack(d, local, matBits);
            d.flush();
            return bo.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ColumnTile decode(byte[] data) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(data));
        int cx = d.readInt(), cz = d.readInt();
        int ci = d.readByte();
        Confidence[] cs = Confidence.values();
        if (ci < 0 || ci >= cs.length) throw new IOException("bad confidence " + ci);
        long at = d.readLong();
        int min = d.readShort();
        int topBits = d.readByte();
        checkBits(topBits);
        int[] v = topBits == 0 ? new int[ColumnTile.AREA] : unpack(d, topBits);
        short[] top = new short[ColumnTile.AREA];
        for (int i = 0; i < top.length; i++) top[i] = (short) (min + v[i]);
        int n = d.readUnsignedShort();
        if (n < 1 || n > ColumnTile.AREA) throw new IOException("bad palette size " + n);
        short[] palette = new short[n];
        for (int k = 0; k < n; k++) palette[k] = d.readShort();
        int matBits = d.readByte();
        checkBits(matBits);
        if (matBits < bitsFor(n - 1)) throw new IOException("matBits too small for palette");
        int[] local = matBits == 0 ? new int[ColumnTile.AREA] : unpack(d, matBits);
        short[] mat = new short[ColumnTile.AREA];
        for (int i = 0; i < mat.length; i++) {
            if (local[i] >= n) throw new IOException("palette index out of range");
            mat[i] = palette[local[i]];
        }
        return new ColumnTile(cx, cz, top, mat, cs[ci], at);
    }

    static int bitsFor(int maxValue) {
        return maxValue <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(maxValue);
    }

    private static void checkBits(int b) throws IOException {
        if (b < 0 || b > 16) throw new IOException("bad bit width " + b);
    }

    private static void pack(DataOutputStream out, int[] values, int bits) throws IOException {
        long acc = 0;
        int have = 0;
        for (int v : values) {
            acc = (acc << bits) | (v & ((1 << bits) - 1));
            have += bits;
            while (have >= 8) { out.writeByte((int) (acc >>> (have - 8))); have -= 8; }
            acc &= (1L << have) - 1;
        }
        if (have > 0) out.writeByte((int) (acc << (8 - have)));
    }

    private static int[] unpack(DataInputStream in, int bits) throws IOException {
        int[] out = new int[ColumnTile.AREA];
        long acc = 0;
        int have = 0;
        for (int i = 0; i < out.length; i++) {
            while (have < bits) { acc = (acc << 8) | in.readUnsignedByte(); have += 8; }
            out[i] = (int) ((acc >>> (have - bits)) & ((1 << bits) - 1));
            have -= bits;
            acc &= (1L << have) - 1;
        }
        return out;
    }

    /** Exposed for tests. */
    static boolean same(ColumnTile a, ColumnTile b) {
        return a.sameContent(b) && a.observedAtMillis() == b.observedAtMillis()
                && Arrays.equals(a.topArray(), b.topArray());
    }
}
