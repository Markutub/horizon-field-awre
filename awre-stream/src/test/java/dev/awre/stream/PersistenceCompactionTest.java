package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.TileLog;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.TerrainStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PersistenceCompactionTest {
    private static ColumnTile flat(int cx, int cz, int y, int mat) {
        short[] top = new short[256], m = new short[256];
        java.util.Arrays.fill(top, (short) y);
        java.util.Arrays.fill(m, (short) mat);
        return new ColumnTile(cx, cz, top, m, Confidence.OBSERVED, 1L);
    }

    @Test void compactionRespectsThresholdsAndPreservesTerrainAcrossRestart() throws Exception {
        Path file = Files.createTempDirectory("awre-compact").resolve("terrain").resolve("w.hfl");
        TerrainStore store = new TerrainStore(4, TerrainStore.Reduce.MEAN, 1000);
        MaterialPalette pal = new MaterialPalette();
        int stone = pal.idOf("minecraft:stone");
        Metrics m = new Metrics();
        TerrainPersistence p = TerrainPersistence.open(file, "fp-pc", store, pal, m);
        assertNull(p.compactIfWorthwhile(0.5, 0), "nothing on disk yet: nothing to compact");

        // 8 chunks, each re-observed with 4 different contents (blocks were edited): 32 appends, 8 survivors
        for (int v = 0; v < 4; v++)
            for (int c = 0; c < 8; c++) { ColumnTile t = flat(c, 3, 60 + v, stone); store.put(t); p.persist(t); }
        assertEquals(32, m.get("persist.appended"));

        assertNull(p.compactIfWorthwhile(0.9, 0), "garbage ratio 0.75 is below the 0.9 threshold");
        assertNull(p.compactIfWorthwhile(0.5, 1L << 30), "file is far below the size threshold");
        assertEquals(0, m.get("persist.compactions"));

        TileLog.CompactReport r = p.compactIfWorthwhile(0.5, 0);
        assertNotNull(r);
        assertEquals(32, r.recordsBefore());
        assertEquals(8, r.recordsAfter());
        assertTrue(r.bytesSaved() > 0);
        assertEquals(1, m.get("persist.compactions"));
        assertEquals(r.bytesSaved(), m.get("persist.compact-saved-bytes"));
        assertNull(p.compactIfWorthwhile(0.5, 0), "right after a compaction the ratio is 0 again");

        // persisting keeps working after the swap (and the deduper still knows the last contents)
        ColumnTile edit = flat(3, 3, 99, stone);
        store.put(edit);
        p.persist(edit);
        p.persist(edit);
        assertEquals(33, m.get("persist.appended"));
        assertEquals(0, m.get("persist.errors"));
        p.close();

        TerrainStore store2 = new TerrainStore(4, TerrainStore.Reduce.MEAN, 1000);
        try (TerrainPersistence p2 = TerrainPersistence.open(file, "fp-pc", store2, new MaterialPalette(), new Metrics())) {
            assertEquals(9, p2.report().tiles());                    // 8 survivors + 1 edit appended after compaction
            assertEquals(8, store2.chunkCount());
            for (int c = 0; c < 8; c++) { LodKey k = new LodKey(0, c, 3); assertTrue(store.tile(k).sameContent(store2.tile(k)), "chunk " + c); }
        }
    }
}
