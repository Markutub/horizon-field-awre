package dev.horizonfield.core;

final class TestUtil {
    private TestUtil() { }

    static ColumnTile flat(int cx, int cz, int y, int mat) {
        short[] top = new short[256], m = new short[256];
        java.util.Arrays.fill(top, (short) y);
        java.util.Arrays.fill(m, (short) mat);
        return new ColumnTile(cx, cz, top, m, Confidence.OBSERVED, 1L);
    }
}
