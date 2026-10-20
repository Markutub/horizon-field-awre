package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TileLogTest {
    @Test
    void roundTripSurvivesReopenAndGarbageTail() throws Exception {
        Path dir = Files.createTempDirectory("hf-test");
        Path f = dir.resolve("w.hfl");
        MaterialPalette pal = new MaterialPalette();
        int stone = pal.idOf("minecraft:stone");
        TileLog.Opened o = TileLog.open(f, "fp1", pal, t -> { });
        assertTrue(o.report().freshStart());
        o.log().append(TestUtil.flat(0, 0, 64, stone));
        o.log().append(TestUtil.flat(1, 0, 70, stone));
        o.log().append(TestUtil.flat(0, 0, 65, stone)); // replaces first on replay
        o.log().close();

        Files.write(f, new byte[]{1, 2, 3, 4, 5, 6, 7}, StandardOpenOption.APPEND);

        MaterialPalette pal2 = new MaterialPalette();
        List<ColumnTile> got = new ArrayList<>();
        TileLog.Opened o2 = TileLog.open(f, "fp1", pal2, got::add);
        assertFalse(o2.report().freshStart());
        assertEquals(3, o2.report().tiles());
        assertTrue(o2.report().discardedBytes() >= 7);
        assertEquals("minecraft:stone", pal2.keyOf(stone));
        assertEquals(65, got.get(2).topAt(3, 3));
        o2.log().close();
    }

    @Test
    void fingerprintMismatchStartsFresh() throws Exception {
        Path f = Files.createTempDirectory("hf-test").resolve("w.hfl");
        MaterialPalette pal = new MaterialPalette();
        TileLog.Opened o = TileLog.open(f, "A", pal, t -> { });
        o.log().append(TestUtil.flat(0, 0, 64, 0));
        o.log().close();
        List<ColumnTile> got = new ArrayList<>();
        TileLog.Opened o2 = TileLog.open(f, "B", new MaterialPalette(), got::add);
        assertTrue(o2.report().freshStart());
        assertTrue(got.isEmpty());
        o2.log().close();
    }

    @Test
    void sharedPaletteAcrossWorldsIsRejectedOnReplay() throws Exception {
        Path dir = Files.createTempDirectory("hf-test");
        // world A writes its own palette: id1 = grass
        MaterialPalette a = new MaterialPalette();
        TileLog.Opened oa = TileLog.open(dir.resolve("a.hfl"), "A", a, t -> { });
        oa.log().append(TestUtil.flat(0, 0, 64, a.idOf("minecraft:grass_block")));
        oa.log().close();
        // world B already used the SAME palette object for something else (the former CaptureService bug)
        MaterialPalette shared = new MaterialPalette();
        shared.idOf("minecraft:netherrack");
        assertThrows(IllegalArgumentException.class, () -> TileLog.open(dir.resolve("a.hfl"), "A", shared, t -> { }));
    }
}
