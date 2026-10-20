package dev.horizonfield.core.terrain;

import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import java.io.IOException;

/**
 * Wire payload of one terrain tile (the "decoded" bytes inside an AWRE TILE message; AWRE deflates and CRCs them).
 * <pre> u8 format(=1) | u8 confidence ordinal | 256 x i16 top (big-endian, row-major z*16+x) | 256 x u16 material </pre>
 * Total {@value #BYTES} bytes. The tile's key is NOT in the payload: it is already in the TILE header.
 */
public final class LodTileCodec {
    public static final int FORMAT = 1;
    public static final int BYTES = 2 + LodTile.AREA * 4;

    private LodTileCodec() { }

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
}
