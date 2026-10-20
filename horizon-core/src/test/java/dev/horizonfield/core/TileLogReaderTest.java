package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TileLogReaderTest {
    private static Path writeLog(String fp) throws Exception {
        Path f = Files.createTempDirectory("hf-reader").resolve("w.hfl");
        MaterialPalette pal = new MaterialPalette();
        int stone = pal.idOf("minecraft:stone");
        TileLog.Opened o = TileLog.open(f, fp, pal, t -> { });
        o.log().append(TestUtil.flat(0, 0, 64, stone));
        o.log().append(TestUtil.flat(-1, 5, 70, stone));
        o.log().close();
        return f;
    }

    @Test
    void readsAnyFingerprintAndNeverModifiesTheFile() throws Exception {
        Path f = writeLog("some-world-fp");
        byte[] before = Files.readAllBytes(f);
        List<ColumnTile> got = new ArrayList<>();
        TileLogReader.Result r = TileLogReader.read(f, got::add);
        assertEquals("some-world-fp", r.fingerprint());
        assertEquals(2, r.records());
        assertEquals(0L, r.discardedBytes());
        assertEquals("minecraft:stone", r.palette().keyOf(1));
        assertEquals(70, got.get(1).topAt(0, 0));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(f)), "reader must not touch the file");
    }

    @Test
    void garbageTailIsReportedAndLeftInPlace() throws Exception {
        Path f = writeLog("fp");
        Files.write(f, new byte[]{9, 9, 9, 9, 9, 9, 9}, StandardOpenOption.APPEND);
        long size = Files.size(f);
        TileLogReader.Result r = TileLogReader.read(f, t -> { });
        assertEquals(2, r.records());
        assertTrue(r.discardedBytes() >= 7);
        assertEquals(size, Files.size(f));
    }

    @Test
    void foreignFileIsRejected() throws Exception {
        Path f = Files.createTempDirectory("hf-reader").resolve("x.hfl");
        Files.write(f, "this is not a tile log at all".getBytes());
        assertThrows(IOException.class, () -> TileLogReader.read(f, t -> { }));
    }
}
