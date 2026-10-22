package dev.horizonfield.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColorOverridesTest {
    @Test void exactBeatsPrefixAndLongerPrefixBeatsShorter() {
        ColorOverrides o = ColorOverrides.parse(List.of("# comment", "", "mymod:* = #101010", "mymod:ore_* = #202020", "mymod:ore_gold = #303030"));
        assertEquals(0xFF303030, (int) o.lookup("mymod:ore_gold"));
        assertEquals(0xFF202020, (int) o.lookup("mymod:ore_iron"));
        assertEquals(0xFF101010, (int) o.lookup("mymod:dirt"));
        assertTrue(o.lookup("other:dirt") == null && o.rejected().isEmpty());
    }

    @Test void badLinesAreRejectedWithAReasonAndGoodOnesStillApply() {
        ColorOverrides o = ColorOverrides.parse(List.of("a:b #123456", "a:c = 123456", "a:d = #12345", "a:e = #GGGGGG", "a:ok = #0000FF"));
        assertEquals(4, o.rejected().size());
        assertEquals(0xFF0000FF, (int) o.lookup("a:ok"));
    }

    @Test void blockColorsUsesOverridesBeforeTheTableButNeverForAir() {
        try {
            BlockColors.setOverrides(ColorOverrides.parse(List.of("minecraft:stone = #FF0000", "minecraft:air = #00FF00", "x:custom = #00FF00")));
            assertEquals(0xFFFF0000, BlockColors.argb("minecraft:stone"));
            assertEquals(0, BlockColors.argb("minecraft:air"));
            assertEquals(0xFF00FF00, BlockColors.argb("x:custom"));
        } finally { BlockColors.setOverrides(null); }
        assertEquals(0xFF707070, BlockColors.argb("minecraft:stone"));
    }
}
