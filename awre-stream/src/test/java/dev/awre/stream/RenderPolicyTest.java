package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.awre.stream.compat.RenderPolicy;
import dev.awre.stream.compat.RenderPolicy.Mode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RenderPolicyTest {
    private static RenderPolicy.Decision d(Mode m, String... loaded) { Set<String> s = Set.of(loaded); return RenderPolicy.decide(m, s::contains); }

    @Test void targetModsNeverSwitchDrawingOff() {
        var r = d(Mode.AUTO, "sodium", "iris", "vulkanmod", "bobby");
        assertTrue(r.renderEnabled());
        assertEquals(4, r.notes().size());
    }

    @Test void overlappingLodModsSwitchOffInAutoButNotWhenForced() {
        assertTrue(!d(Mode.AUTO, "distanthorizons").renderEnabled());
        assertTrue(!d(Mode.AUTO, "voxy", "sodium").renderEnabled());
        assertTrue(d(Mode.ON, "distanthorizons").renderEnabled());
        assertTrue(!d(Mode.OFF).renderEnabled());
        assertTrue(d(Mode.AUTO).renderEnabled() && d(Mode.AUTO).notes().isEmpty());
    }

    @Test void modeParsingIsForgiving() {
        assertEquals(Mode.AUTO, Mode.parse(null)); assertEquals(Mode.AUTO, Mode.parse("banana"));
        assertEquals(Mode.ON, Mode.parse(" ON ")); assertEquals(Mode.OFF, Mode.parse("false"));
    }
}
