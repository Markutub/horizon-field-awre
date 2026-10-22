package dev.horizonfield.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.horizonfield.core.ChunkView;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.TerrainAnalyzer;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiomeTintTest {
    @Test void tagOnlyTintableBlocksAndOnlyWhenTheKeyStaysValid() {
        assertEquals("minecraft:grass_block/biome/minecraft:desert", BiomeTint.tag("minecraft:grass_block", "minecraft:desert"));
        assertEquals("minecraft:stone", BiomeTint.tag("minecraft:stone", "minecraft:desert"));
        assertEquals("minecraft:grass_block", BiomeTint.tag("minecraft:grass_block", null));
        assertEquals("minecraft:water", BiomeTint.tag("minecraft:water", "Bad Biome"), "invalid characters: plain key");
        assertEquals("minecraft:water", BiomeTint.tag("minecraft:water", "x:" + "a".repeat(100)), "too long: plain key");
        String k = BiomeTint.tag("minecraft:oak_leaves", "terralith:volcanic_peaks");
        assertEquals("minecraft:oak_leaves", BiomeTint.baseOf(k));
        assertEquals("terralith:volcanic_peaks", BiomeTint.biomeOf(k));
        assertTrue(!BiomeTint.isTagged("minecraft:stone"));
    }

    @Test void taggedKeysGetBiomeColoursAndUnknownBiomesUseTheHeuristic() {
        int plains = BlockColors.argb(BiomeTint.tag("minecraft:grass_block", "minecraft:plains"));
        int desert = BlockColors.argb(BiomeTint.tag("minecraft:grass_block", "minecraft:desert"));
        assertTrue(plains != desert);
        assertEquals(desert, BlockColors.argb(BiomeTint.tag("minecraft:grass_block", "somemod:red_desert_dunes")), "keyword: desert");
        assertEquals(BlockColors.argb(BiomeTint.tag("minecraft:water", "minecraft:warm_ocean")), BlockColors.argb(BiomeTint.tag("minecraft:water", "somemod:coral_sea")));
        assertEquals(plains, BlockColors.argb(BiomeTint.tag("minecraft:grass_block", "somemod:zzz")), "no keyword: plains");
        assertEquals(0xFF000000, BlockColors.argb("minecraft:grass_block/biome/minecraft:plains") & 0xFF000000, "opaque");
    }

    @Test void overridesWinAndBadLinesAreReported() {
        var p = BiomeTint.parse(List.of("# c", "terralith:volcanic_peaks = grass:#112233 water:#445566", "nocolon = grass:#112233", "a:b = grass:112233", "a:c = sky:#112233"));
        assertEquals(3, p.rejected().size());
        try {
            BiomeTint.setOverrides(p.overrides());
            assertEquals(0xFF112233, BlockColors.argb(BiomeTint.tag("minecraft:grass_block", "terralith:volcanic_peaks")));
            assertEquals(0xFF445566, BlockColors.argb(BiomeTint.tag("minecraft:water", "terralith:volcanic_peaks")));
            int foliageDefault = BlockColors.argb(BiomeTint.tag("minecraft:oak_leaves", "terralith:volcanic_peaks"));
            assertTrue(foliageDefault != 0xFF112233, "unset slot falls back to the table/heuristic");
        } finally { BiomeTint.setOverrides(null); }
    }

    @Test void blockColorOverrideBeatsTheTintAndATagOnAPlainBlockIsIgnored() {
        try {
            BlockColors.setOverrides(ColorOverrides.parse(List.of("minecraft:grass_block = #010203")));
            assertEquals(0xFF010203, BlockColors.argb("minecraft:grass_block/biome/minecraft:plains"));
        } finally { BlockColors.setOverrides(null); }
        assertEquals(BlockColors.argb("minecraft:stone"), BlockColors.argb("minecraft:stone/biome/minecraft:plains"));
    }

    @Test void analyzerPutsBiomeIntoThePaletteOnlyForTintableBlocks() {
        MaterialPalette pal = new MaterialPalette();
        ChunkView v = new ChunkView() {
            @Override public int chunkX() { return 0; } @Override public int chunkZ() { return 0; } @Override public int minY() { return 0; }
            @Override public int highestBlockY(int x, int z) { return 64; }
            @Override public String blockKey(int x, int y, int z) { return x < 8 ? "minecraft:grass_block" : "minecraft:stone"; }
            @Override public String biomeKey(int x, int y, int z) { return z < 8 ? "minecraft:desert" : "minecraft:plains"; }
        };
        ColumnTile t = new TerrainAnalyzer(pal).analyze(v, Confidence.OBSERVED, 0);
        assertTrue(pal.snapshot().contains("minecraft:grass_block/biome/minecraft:desert"));
        assertTrue(pal.snapshot().contains("minecraft:grass_block/biome/minecraft:plains"));
        assertTrue(pal.snapshot().contains("minecraft:stone") && !pal.snapshot().contains("minecraft:stone/biome/minecraft:desert"));
        // a view that cannot tell the biome (default method) keeps the plain key
        MaterialPalette pal2 = new MaterialPalette();
        new TerrainAnalyzer(pal2).analyze(new ChunkView() {
            @Override public int chunkX() { return 0; } @Override public int chunkZ() { return 0; } @Override public int minY() { return 0; }
            @Override public int highestBlockY(int x, int z) { return 64; }
            @Override public String blockKey(int x, int y, int z) { return "minecraft:grass_block"; }
        }, Confidence.OBSERVED, 0);
        assertEquals(List.of("minecraft:air", "minecraft:grass_block"), pal2.snapshot());
    }

    @Test void taggedKeysAreValidPaletteKeysForTheProtocol() {
        // PaletteUpdate.validKey accepts [a-z0-9_.-] plus ':' and '/'; the tag uses exactly those
        for (String b : new String[] {"minecraft:grass_block", "minecraft:water", "minecraft:dark_oak_leaves"}) {
            String k = BiomeTint.tag(b, "terralith:alpine_highlands");
            assertTrue(k.length() <= 96 && k.matches("[a-z0-9_.:/-]+") && k.indexOf(':') >= 0, k);
        }
    }
}
