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

    @Test
    void compactionKeepsTheLastVersionOfEveryChunkAndShrinksTheFile() throws Exception {
        Path f = Files.createTempDirectory("hf-test").resolve("w.hfl");
        MaterialPalette pal = new MaterialPalette();
        int stone = pal.idOf("minecraft:stone"), grass = pal.idOf("minecraft:grass_block");
        TileLog.Opened o = TileLog.open(f, "fp-c", pal, t -> { });
        TileLog log = o.log();
        for (int version = 0; version < 5; version++)
            for (int c = 0; c < 6; c++) log.append(TestUtil.flat(c, 0, 60 + version, c % 2 == 0 ? stone : grass));
        log.append(TestUtil.flat(100, -7, 90, stone));
        assertEquals(31, log.tileRecords());
        assertEquals(7, log.liveTiles());
        assertTrue(log.garbageRatio() > 0.75, "ratio " + log.garbageRatio());
        long sizeBefore = log.sizeBytes();

        TileLog.CompactReport r = log.compact();
        assertEquals(31, r.recordsBefore());
        assertEquals(7, r.recordsAfter());
        assertEquals(sizeBefore, r.bytesBefore());
        assertTrue(r.bytesAfter() < r.bytesBefore() / 3, "after " + r.bytesAfter() + " before " + r.bytesBefore());
        assertEquals(r.bytesAfter(), log.sizeBytes());
        assertEquals(7, log.tileRecords());
        assertEquals(0.0, log.garbageRatio());
        assertFalse(Files.exists(f.resolveSibling("w.hfl.compact")), "temp file must be gone after the swap");

        // the log keeps working after the swap: appends land at the end of the NEW file
        log.append(TestUtil.flat(2, 0, 123, stone));
        log.close();

        MaterialPalette pal2 = new MaterialPalette();
        java.util.Map<Long, ColumnTile> last = new java.util.HashMap<>();
        TileLog.Opened o2 = TileLog.open(f, "fp-c", pal2, t -> last.put(HeightPyramid.key(t.chunkX(), t.chunkZ()), t));
        assertEquals(8, o2.report().tiles());                       // 7 survivors + 1 appended after compaction
        assertEquals(0L, o2.report().discardedBytes());
        assertEquals("minecraft:stone", pal2.keyOf(stone));
        assertEquals("minecraft:grass_block", pal2.keyOf(grass));
        assertEquals(7, last.size());
        assertEquals(64, last.get(HeightPyramid.key(0, 0)).topAt(0, 0));     // version 4 (60 + 4) survived, not version 0
        assertEquals(123, last.get(HeightPyramid.key(2, 0)).topAt(5, 5));    // the post-compaction append wins
        assertEquals(90, last.get(HeightPyramid.key(100, -7)).topAt(1, 1));  // negative coordinates round-trip through the key
        assertEquals(7, o2.log().liveTiles());
        o2.log().close();
    }

    @Test
    void compactionIsIdempotentAndWorksOnAnEmptyLog() throws Exception {
        Path f = Files.createTempDirectory("hf-test").resolve("w.hfl");
        TileLog empty = TileLog.open(f, "fp-e", new MaterialPalette(), t -> { }).log();
        TileLog.CompactReport r0 = empty.compact();
        assertEquals(0, r0.recordsBefore());
        assertEquals(0, r0.recordsAfter());
        assertEquals(r0.bytesBefore(), r0.bytesAfter());
        empty.append(TestUtil.flat(1, 1, 70, 0));
        empty.append(TestUtil.flat(1, 1, 71, 0));
        TileLog.CompactReport r1 = empty.compact();
        TileLog.CompactReport r2 = empty.compact();
        assertEquals(1, r1.recordsAfter());
        assertEquals(r1.bytesAfter(), r2.bytesBefore());
        assertEquals(r1.bytesAfter(), r2.bytesAfter());
        empty.close();
        List<ColumnTile> got = new ArrayList<>();
        TileLog.open(f, "fp-e", new MaterialPalette(), got::add).log().close();
        assertEquals(1, got.size());
        assertEquals(71, got.get(0).topAt(0, 0));
    }

    @Test
    void compactionDropsATornTailAndALeftoverTempFileIsIgnored() throws Exception {
        Path f = Files.createTempDirectory("hf-test").resolve("w.hfl");
        TileLog log = TileLog.open(f, "fp-t", new MaterialPalette(), t -> { }).log();
        log.append(TestUtil.flat(0, 0, 64, 0));
        log.append(TestUtil.flat(0, 0, 65, 0));
        log.append(TestUtil.flat(1, 0, 66, 0));
        log.flush();
        // a half-written record behind the live channel position (what a crash mid-append leaves; open() would cut it, compact() must too)
        long good = log.sizeBytes();
        try (java.nio.channels.FileChannel x = java.nio.channels.FileChannel.open(f, StandardOpenOption.WRITE)) {
            x.position(good);
            x.write(java.nio.ByteBuffer.wrap(new byte[]{2, 0, 0, 4, 0, 9, 9, 9}));
        }
        TileLog.CompactReport r = log.compact();
        assertEquals(3, r.recordsBefore());
        assertEquals(2, r.recordsAfter());
        log.close();

        // a temp file left by a crash before the swap must neither be read nor survive the next open
        Path tmp = f.resolveSibling("w.hfl.compact");
        Files.write(tmp, new byte[]{9, 9, 9});
        List<ColumnTile> got = new ArrayList<>();
        TileLog.Opened o = TileLog.open(f, "fp-t", new MaterialPalette(), got::add);
        assertFalse(Files.exists(tmp));
        assertEquals(2, got.size());
        assertEquals(0L, o.report().discardedBytes());
        o.log().close();
    }

    @Test
    void streamedReplayHandlesRecordsCrossingTheReadWindowAndKeepsOrder() throws Exception {
        Path f = Files.createTempDirectory("hf-test").resolve("big.hfl");
        MaterialPalette pal = new MaterialPalette();
        int stone = pal.idOf("minecraft:stone");
        TileLog.Opened o = TileLog.open(f, "fp-big", pal, t -> { });
        int n = 6000;
        for (int i = 0; i < n; i++) o.log().append(TestUtil.flat(i % 100, i / 100, 40 + (i % 50), stone));
        o.log().close();
        assertTrue(Files.size(f) > 3 * (1 << 16), "premise: the log spans several 64 KiB read windows: " + Files.size(f));

        MaterialPalette pal2 = new MaterialPalette();
        List<ColumnTile> got = new ArrayList<>();
        TileLog.Opened o2 = TileLog.open(f, "fp-big", pal2, got::add);
        assertFalse(o2.report().freshStart());
        assertEquals(n, o2.report().tiles());
        assertEquals(0, o2.report().discardedBytes());
        assertEquals(n, got.size());
        for (int i = 0; i < n; i += 97) {
            assertEquals(i % 100, got.get(i).chunkX(), "order preserved at " + i);
            assertEquals(40 + (i % 50), got.get(i).topAt(3, 3));
        }
        o2.log().close();
    }

    @Test
    void aDamagedHugeLengthFieldEndsTheReplayAsADiscardedTailWithoutAllocatingIt() throws Exception {
        Path f = Files.createTempDirectory("hf-test").resolve("len.hfl");
        MaterialPalette pal = new MaterialPalette();
        int stone = pal.idOf("minecraft:stone");
        TileLog.Opened o = TileLog.open(f, "fp-len", pal, t -> { });
        o.log().append(TestUtil.flat(0, 0, 64, stone));
        o.log().close();
        long good = Files.size(f);
        // a record header claiming ~2 GiB, then a little data: must stop and discard, not try to allocate or read it
        Files.write(f, new byte[]{1, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xF0, 9, 9, 9, 9, 9, 9, 9, 9, 9}, StandardOpenOption.APPEND);

        List<ColumnTile> got = new ArrayList<>();
        TileLog.Opened o2 = TileLog.open(f, "fp-len", new MaterialPalette(), got::add);
        assertFalse(o2.report().freshStart());
        assertEquals(1, o2.report().tiles());
        assertEquals(14, o2.report().discardedBytes());
        o2.log().close();
        assertEquals(good, Files.size(f), "the damaged tail is truncated away");
    }

    @Test
    void aRealIoErrorIsNotMistakenForAnUnreadableHeaderAndDoesNotTruncateTheLog() throws Exception {
        Path dir = Files.createTempDirectory("hf-test");
        Path f = dir.resolve("io.hfl");
        MaterialPalette pal = new MaterialPalette();
        int stone = pal.idOf("minecraft:stone");
        TileLog.Opened o = TileLog.open(f, "fp-io", pal, t -> { });
        o.log().append(TestUtil.flat(0, 0, 64, stone));
        o.log().close();
        long before = Files.size(f);
        // a directory where the file should be cannot be opened for reading as a file: the error must surface, and nothing may be rewritten
        Path asDir = dir.resolve("dir.hfl");
        Files.createDirectory(asDir);
        assertThrows(java.io.IOException.class, () -> TileLog.open(asDir, "fp-io", new MaterialPalette(), t -> { }));
        assertTrue(Files.isDirectory(asDir));
        assertEquals(before, Files.size(f));
    }
}
