package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ColumnTilePackingTest {
    private static ColumnTile make(Random r, int base, int range, int palette) {
        short[] top = new short[256], mat = new short[256];
        short[] ids = new short[palette];
        for (int i = 0; i < palette; i++) ids[i] = (short) r.nextInt(65536);
        for (int i = 0; i < 256; i++) { top[i] = (short) (base + (range == 0 ? 0 : r.nextInt(range + 1))); mat[i] = ids[r.nextInt(palette)]; }
        return new ColumnTile(-5, 9, top, mat, Confidence.OBSERVED, 123L);
    }

    /** What the pre-packing implementation computed, kept here as the oracle. */
    private static short oracleDominant(short[] mat) {
        java.util.Map<Short, Integer> counts = new java.util.HashMap<>();
        short best = 0; int bestCount = -1;
        for (short m : mat) { int c = counts.merge(m, 1, Integer::sum); if (c > bestCount) { bestCount = c; best = m; } }
        return best;
    }

    @Test void everyColumnRoundTripsForRandomTilesIncludingExtremes() {
        Random r = new Random(42);
        int[][] cases = {{64, 0, 1}, {64, 3, 2}, {60, 40, 6}, {-64, 500, 12}, {-32768, 65535, 256}, {32767, 0, 1}, {-1, 1, 2}, {0, 255, 30}, {0, 256, 30}, {-20000, 40000, 256}};
        for (int rep = 0; rep < 60; rep++) {
            for (int[] c : cases) {
                int range = Math.min(c[1], 32767 - Math.max(c[0], -32768) + (c[0] < 0 ? 32768 : 0));
                int base = c[0];
                short[] top = new short[256], mat = new short[256], ids = new short[c[2]];
                for (int i = 0; i < ids.length; i++) ids[i] = (short) r.nextInt(65536);
                for (int i = 0; i < 256; i++) {
                    long t = (long) base + (range == 0 ? 0 : r.nextInt(range + 1));
                    top[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, t));
                    mat[i] = ids[r.nextInt(ids.length)];
                }
                ColumnTile t = new ColumnTile(1, 2, top, mat, Confidence.PREDICTED, 7L);
                for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                    assertEquals(top[lz * 16 + lx], t.topAt(lx, lz), "top " + lx + "," + lz + " case " + java.util.Arrays.toString(c));
                    assertEquals(mat[lz * 16 + lx] & 0xFFFF, t.materialAt(lx, lz) & 0xFFFF, "mat " + lx + "," + lz);
                }
                assertTrue(java.util.Arrays.equals(top, t.topArray()));
                assertTrue(java.util.Arrays.equals(mat, t.materialArray()));
                short mn = Short.MAX_VALUE, mx = Short.MIN_VALUE;
                for (short s : top) { mn = (short) Math.min(mn, s); mx = (short) Math.max(mx, s); }
                assertEquals(mn, t.minTop());
                assertEquals(mx, t.maxTop());
                assertEquals(oracleDominant(mat), (short) t.dominantMaterial(), "dominant material keeps the original tie-break");
            }
        }
    }

    @Test void inputArraysAreNotRetainedAndReturnedArraysAreCopies() {
        short[] top = new short[256], mat = new short[256];
        java.util.Arrays.fill(top, (short) 70);
        ColumnTile t = new ColumnTile(0, 0, top, mat, Confidence.OBSERVED, 1L);
        top[0] = 5; mat[0] = 9;
        assertEquals(70, t.topAt(0, 0));
        assertEquals(0, t.materialAt(0, 0));
        t.topArray()[1] = 1;
        assertEquals(70, t.topAt(1, 0));
    }

    @Test void sameContentAndHashFollowTheContentNotTheRepresentationOrTheTimestamp() {
        Random a = new Random(5), b = new Random(5);
        ColumnTile x = make(a, 64, 9, 4), y = make(b, 64, 9, 4);
        assertTrue(x.sameContent(y));
        assertEquals(x.contentHash64(), y.contentHash64());
        short[] top = x.topArray(), mat = x.materialArray();
        ColumnTile later = new ColumnTile(-5, 9, top, mat, Confidence.OBSERVED, 999_999L);
        assertTrue(x.sameContent(later), "observedAt is not content");
        top[17]++;
        assertFalse(x.sameContent(new ColumnTile(-5, 9, top, mat, Confidence.OBSERVED, 1L)));
        top[17]--; mat[200] = (short) (mat[200] + 1);
        assertFalse(x.sameContent(new ColumnTile(-5, 9, top, mat, Confidence.OBSERVED, 1L)));
        assertFalse(x.sameContent(new ColumnTile(-5, 9, x.topArray(), x.materialArray(), Confidence.PREDICTED, 1L)), "confidence is content");
        assertFalse(x.sameContent(new ColumnTile(-4, 9, x.topArray(), x.materialArray(), Confidence.OBSERVED, 1L)), "coordinates are content");
    }

    @Test void contentHashIsTheSameValueAsBeforeThePacking() {
        short[] top = new short[256], mat = new short[256];
        for (int i = 0; i < 256; i++) { top[i] = (short) (60 + (i * 7) % 13); mat[i] = (short) (1 + i % 5); }
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < 256; i++) { h = (h ^ (top[i] & 0xFFFF)) * 0x100000001b3L; h = (h ^ (mat[i] & 0xFFFF)) * 0x100000001b3L; }
        h = (h ^ Confidence.OBSERVED.ordinal()) * 0x100000001b3L;
        assertEquals(h, new ColumnTile(0, 0, top, mat, Confidence.OBSERVED, 1L).contentHash64());
    }

    @Test void typicalTilesAreMuchSmallerThanTwoShortArraysAndTheWorstCaseIsBounded() {
        Random r = new Random(1);
        ColumnTile flat = make(r, 64, 0, 1), hilly = make(r, 60, 25, 6), worst = make(r, -32768, 65535, 256);
        int raw = 72 + 2 * (16 + 512);                             // the previous layout: header + two short[256]
        assertTrue(flat.approxHeapBytes() < raw / 5, "flat " + flat.approxHeapBytes());
        assertTrue(hilly.approxHeapBytes() < raw / 2, "hilly " + hilly.approxHeapBytes());
        assertTrue(worst.approxHeapBytes() <= raw + 600, "worst " + worst.approxHeapBytes() + " must stay bounded (palette 256 + 16-bit heights)");
    }
}
