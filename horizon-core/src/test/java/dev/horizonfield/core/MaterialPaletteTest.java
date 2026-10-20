package dev.horizonfield.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MaterialPaletteTest {
    @Test
    void airIsZeroAndIdsAreStable() {
        MaterialPalette p = new MaterialPalette();
        assertEquals(0, p.idOf("minecraft:air"));
        int stone = p.idOf("minecraft:stone");
        assertEquals(stone, p.idOf("minecraft:stone"));
        assertEquals("minecraft:stone", p.keyOf(stone));
        assertEquals(2, p.size());
    }

    @Test
    void restoreRejectsGapsAndMismatch() {
        MaterialPalette p = new MaterialPalette();
        assertThrows(IllegalStateException.class, () -> p.restore(5, "minecraft:dirt"));
        p.restore(1, "minecraft:dirt");
        assertThrows(IllegalStateException.class, () -> p.restore(1, "minecraft:sand"));
    }
}
