package dev.horizonfield.core.terrain;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.lod.LodKey;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LodTileCodecV2Test {
    private static final LodKey K = new LodKey(0, 3, -2);

    private static LodTile tile(Random r, int range, int palette, double missing, int base) {
        short[] top = new short[256], mat = new short[256];
        short[] ids = new short[palette];
        for (int i = 0; i < palette; i++) ids[i] = (short) r.nextInt(65536);
        for (int i = 0; i < 256; i++) {
            if (r.nextDouble() < missing) { top[i] = LodTile.NO_DATA; mat[i] = 0; continue; }
            top[i] = (short) (base + (range == 0 ? 0 : r.nextInt(range + 1)));
            mat[i] = ids[r.nextInt(palette)];
        }
        return new LodTile(K, top, mat, Confidence.values()[r.nextInt(Confidence.values().length)]);
    }

    @Test void randomTilesRoundTripAndHaveExactlyOneEncoding() throws IOException {
        Random r = new Random(7);
        int[] ranges = {0, 1, 2, 5, 40, 300, 4000, 32000};
        for (int i = 0; i < 600; i++) {
            int range = ranges[r.nextInt(ranges.length)];
            int base = range >= 32000 ? -32767 : r.nextInt(600) - 100;
            LodTile t = tile(r, range, 1 + r.nextInt(i % 7 == 0 ? 256 : 6), i % 3 == 0 ? 0.0 : r.nextDouble(), base);
            byte[] b = LodTileCodec.encodeV2(t);
            LodTile back = LodTileCodec.decode(K, b);
            assertTrue(t.sameContent(back), "round trip #" + i);
            assertArrayEquals(b, LodTileCodec.encodeV2(back), "canonical #" + i);
        }
    }

    @Test void edgeTilesAndSizes() throws IOException {
        Random r = new Random(1);
        LodTile flat = tile(r, 0, 1, 0, 64);
        assertEquals(6 + 2 + 2 + 1, LodTileCodec.encodeV2(flat).length);          // header + palette(1) + matBits, no packed data
        assertTrue(flat.sameContent(LodTileCodec.decode(K, LodTileCodec.encodeV2(flat))));
        LodTile empty = tile(r, 0, 1, 1.0, 0);
        byte[] be = LodTileCodec.encodeV2(empty);
        assertEquals(0, be[4]);
        assertTrue(empty.sameContent(LodTileCodec.decode(K, be)));
        assertEquals(0, empty.validSamples());
        LodTile smooth = tile(r, 6, 3, 0, 60);
        assertTrue(LodTileCodec.encodeV2(smooth).length < LodTileCodec.BYTES / 3, "v2 " + LodTileCodec.encodeV2(smooth).length);
        // v1 payloads still decode through the same entry point
        assertTrue(smooth.sameContent(LodTileCodec.decode(K, LodTileCodec.encode(smooth))));
    }

    @Test void everyTruncationAndTrailingByteIsRejected() {
        Random r = new Random(3);
        for (double missing : new double[]{0.0, 0.3}) {
            byte[] good = LodTileCodec.encodeV2(tile(r, 25, 4, missing, 50));
            for (int len = 0; len < good.length; len++) {
                final int n = len;
                assertThrows(IOException.class, () -> LodTileCodec.decode(K, java.util.Arrays.copyOf(good, n)));
            }
            byte[] longer = java.util.Arrays.copyOf(good, good.length + 1);
            assertThrows(IOException.class, () -> LodTileCodec.decode(K, longer));
        }
    }

    @Test void corruptedBytesNeverCrashTheDecoder() {
        Random r = new Random(11);
        byte[] good = LodTileCodec.encodeV2(tile(r, 80, 5, 0.2, 40));
        for (int i = 0; i < 20000; i++) {
            byte[] b = good.clone();
            int flips = 1 + r.nextInt(3);
            for (int f = 0; f < flips; f++) b[r.nextInt(b.length)] ^= (byte) (1 << r.nextInt(8));
            try {
                LodTile t = LodTileCodec.decode(K, b);
                // accepted: then it must be a valid tile whose canonical re-encoding decodes to the same content
                assertTrue(t.sameContent(LodTileCodec.decode(K, LodTileCodec.encodeV2(t))));
            } catch (IOException expected) {
                // the only allowed failure mode
            }
        }
    }

    @Test void specificMalformedHeadersAreRejected() {
        Random r = new Random(5);
        byte[] g = LodTileCodec.encodeV2(tile(r, 10, 2, 0.0, 70));
        byte[] a = g.clone(); a[1] = 9;  assertThrows(IOException.class, () -> LodTileCodec.decode(K, a));   // confidence
        byte[] b = g.clone(); b[4] = 16; assertThrows(IOException.class, () -> LodTileCodec.decode(K, b));   // topBits
        byte[] c = g.clone(); c[5] = 2;  assertThrows(IOException.class, () -> LodTileCodec.decode(K, c));   // unknown flag
        byte[] d = g.clone(); d[5] = 1;  assertThrows(IOException.class, () -> LodTileCodec.decode(K, d));   // bitmap flag without bitmap payload
    }
}
