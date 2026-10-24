package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.awre.stream.compat.VanillaRadius;
import dev.awre.stream.compat.VanillaRadius.Mode;
import org.junit.jupiter.api.Test;

class VanillaRadiusTest {
    @Test void effectiveIsTheDefaultAndUsesTheServerLimitedValue() {
        assertEquals(Mode.EFFECTIVE, Mode.parse(null));
        assertEquals(Mode.EFFECTIVE, Mode.parse("banana"));
        assertEquals(Mode.EFFECTIVE, Mode.parse("effective"));
        assertEquals(4, VanillaRadius.chunks(Mode.EFFECTIVE, 12, 4));
    }

    @Test void clientModeUsesTheRawOptionAndParsingIsForgiving() {
        assertEquals(Mode.CLIENT, Mode.parse(" CLIENT "));
        assertEquals(12, VanillaRadius.chunks(Mode.CLIENT, 12, 4));
    }

    @Test void neverNegative() {
        assertEquals(0, VanillaRadius.chunks(Mode.EFFECTIVE, 12, -3));
        assertEquals(0, VanillaRadius.chunks(Mode.CLIENT, -1, 4));
    }
}
