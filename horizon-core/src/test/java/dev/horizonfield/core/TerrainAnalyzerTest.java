package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TerrainAnalyzerTest {
    private record FakeView(int chunkX, int chunkZ) implements ChunkView {
        public int minY() { return -64; }
        public int highestBlockY(int lx, int lz) { return lx == 0 && lz == 0 ? -65 : 64 + lx; }
        public String blockKey(int lx, int y, int lz) { return lx % 2 == 0 ? "minecraft:grass_block" : "minecraft:sand"; }
    }

    @Test
    void producesTileWithPaletteIdsAndEmptyColumns() {
        MaterialPalette pal = new MaterialPalette();
        ColumnTile t = new TerrainAnalyzer(pal).analyze(new FakeView(2, -3), Confidence.OBSERVED, 42L);
        assertEquals(2, t.chunkX());
        assertEquals(-3, t.chunkZ());
        assertEquals(-65, t.topAt(0, 0));
        assertEquals(MaterialPalette.AIR, t.materialAt(0, 0));
        assertEquals(64 + 5, t.topAt(5, 7));
        assertEquals("minecraft:sand", pal.keyOf(t.materialAt(5, 7)));
        assertEquals(64 + 15, t.maxTop());
    }
}
