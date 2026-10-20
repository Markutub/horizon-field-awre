package dev.awre.protocol;

/** G0 test payload: a deterministic 16x16 big-endian int16 height grid, so the receiver can verify content end to end. */
public final class TestTile {
    private TestTile() { }

    public static final int SIDE = 16;
    public static final int BYTES = SIDE * SIDE * 2;

    public static byte[] generate(int tileX, int tileZ, int lod, long revision) {
        byte[] b = new byte[BYTES];
        int i = 0;
        for (int z = 0; z < SIDE; z++) for (int x = 0; x < SIDE; x++) {
            long h = 64 + Math.floorMod((long) x * 3 + (long) z * 5 + (long) tileX * 31 + (long) tileZ * 17 + (long) lod * 7 + revision * 11, 40L);
            b[i++] = (byte) (h >>> 8); b[i++] = (byte) h;
        }
        return b;
    }

    /** Incompressible-ish side x side int16 grid (hash noise) used to force fragmentation. side must be >= 16. */
    public static byte[] generateNoise(int tileX, int tileZ, int lod, long revision, int side) {
        if (side < SIDE || side > 512) throw new IllegalArgumentException("side " + side);
        byte[] b = new byte[side * side * 2];
        long state = 0x9E3779B97F4A7C15L ^ ((long) tileX * 0xBF58476D1CE4E5B9L) ^ ((long) tileZ * 0x94D049BB133111EBL) ^ ((long) lod << 56) ^ revision;
        for (int i = 0; i < b.length; i += 2) {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z ^= z >>> 31;
            int h = 64 + (int) Math.floorMod(z, 256L);
            b[i] = (byte) (h >>> 8); b[i + 1] = (byte) h;
        }
        return b;
    }

    public static boolean verify(int tileX, int tileZ, int lod, long revision, byte[] decoded) {
        if (decoded.length == BYTES) return java.util.Arrays.equals(generate(tileX, tileZ, lod, revision), decoded);
        if (decoded.length % 2 != 0) return false;
        int side = (int) Math.round(Math.sqrt(decoded.length / 2.0));
        if (side < SIDE || side > 512 || side * side * 2 != decoded.length) return false;
        return java.util.Arrays.equals(generateNoise(tileX, tileZ, lod, revision, side), decoded);
    }
}
