package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TileCodecTest {
    @Test
    void flatTileIsTiny() throws IOException {
        ColumnTile t = TestUtil.flat(-3, 7, 64, 1);
        byte[] b = TileCodec.encode(t);
        assertTrue(b.length <= 30, "flat tile should be ~27 bytes, was " + b.length);
        assertTrue(TileCodec.same(t, TileCodec.decode(b)));
    }

    @Test
    void randomTilesRoundTripIncludingNegativeHeights() throws IOException {
        Random r = new Random(42);
        for (int iter = 0; iter < 300; iter++) {
            short[] top = new short[256], mat = new short[256];
            int base = r.nextInt(400) - 64;
            int spread = 1 + r.nextInt(300);
            int pal = 1 + r.nextInt(256);
            short[] ids = new short[pal];
            for (int i = 0; i < pal; i++) ids[i] = (short) r.nextInt(2000);
            for (int i = 0; i < 256; i++) {
                top[i] = (short) (base + r.nextInt(spread));
                mat[i] = ids[r.nextInt(pal)];
            }
            ColumnTile t = new ColumnTile(r.nextInt(2000) - 1000, r.nextInt(2000) - 1000, top, mat,
                    Confidence.values()[r.nextInt(Confidence.values().length)], r.nextLong());
            assertTrue(TileCodec.same(t, TileCodec.decode(TileCodec.encode(t))), "iteration " + iter);
        }
    }

    @Test
    void truncatedAndCorruptInputIsRejected() {
        byte[] b = TileCodec.encode(TestUtil.flat(0, 0, 10, 1));
        assertThrows(IOException.class, () -> TileCodec.decode(java.util.Arrays.copyOf(b, 10)));
        byte[] bad = b.clone();
        bad[8] = 99; // confidence ordinal
        assertThrows(IOException.class, () -> TileCodec.decode(bad));
    }
}
