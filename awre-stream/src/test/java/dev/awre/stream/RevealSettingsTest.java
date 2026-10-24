package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.stream.RevealAnimator.Params;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RevealSettingsTest {
    @Test void parsesKeysCommentsAndBlankLinesAndNormalisesEnabled() {
        var p = RevealSettings.parse(List.of("# comment", "", "enabled = FALSE   # trailing comment", "speed=200", "  peak =  8.5  ", "duration = 2"));
        assertEquals(List.of(), p.rejected());
        assertEquals("off", p.values().get("enabled"));
        assertEquals("200", p.values().get("speed"));
        assertEquals("8.5", p.values().get("peak"));
        assertEquals(4, p.values().size());
        assertEquals("on", RevealSettings.parse(List.of("enabled = yes")).values().get("enabled"));
        assertEquals("on", RevealSettings.parse(List.of("enabled = 1")).values().get("enabled"));
    }

    @Test void badLinesAreRejectedWithTheirLineNumberAndGoodOnesStillCount() {
        var p = RevealSettings.parse(List.of("speed = 150", "nonsense", "colour = red", "peak = lots", "depth =", "enabled = maybe", "emerge = 0.4"));
        assertEquals(Map.of("speed", "150", "emerge", "0.4"), p.values());
        assertEquals(5, p.rejected().size());
        assertTrue(p.rejected().get(0).startsWith("line 2:"), p.rejected().get(0));
        assertTrue(p.rejected().get(1).contains("unknown key 'colour'"));
        assertTrue(p.rejected().get(2).contains("not a number"));
        assertTrue(p.rejected().get(3).contains("no value"));
        assertTrue(p.rejected().get(4).contains("'enabled' must be true or false"));
    }

    @Test void aJvmPropertyAlwaysBeatsTheFileAndTheFileBeatsTheDefaults() {
        RevealSettings.setFileValues(Map.of("speed", "200", "peak", "9", "enabled", "off"));
        Map<String, String> props = new HashMap<>();
        var get = RevealSettings.provider(props::get);
        assertEquals("200", get.apply("awre.reveal.speed"));
        assertFalse(RevealSettings.enabled(get), "the file turns it off");
        props.put("awre.reveal.speed", "300"); props.put("awre.reveal", "on");
        assertEquals("300", get.apply("awre.reveal.speed"), "a property wins over the file");
        assertTrue(RevealSettings.enabled(get), "-Dawre.reveal=on wins over enabled=false");
        assertNull(get.apply("awre.reveal.depth"));
        assertNull(get.apply("something.else"));
        Params p = Params.fromProperties(get, m -> fail("no warnings expected: " + m));
        assertEquals(300, p.speed(), 0); assertEquals(9, p.peak(), 0); assertEquals(Params.DEFAULT.depth(), p.depth(), 0);
        RevealSettings.setFileValues(Map.of());                                // do not leak into other tests
    }

    @Test void theShippedExampleParsesCleanlyAndEqualsTheDefaults() {
        var p = RevealSettings.parse(List.of(RevealSettings.EXAMPLE.split("\n")));
        assertEquals(List.of(), p.rejected());
        assertEquals(RevealSettings.KEYS.size(), p.values().size(), "every documented key is present in the example");
        Map<String, String> m = new HashMap<>();
        p.values().forEach((k, v) -> m.put(k.equals("enabled") ? "awre.reveal" : "awre.reveal." + k, v));
        assertEquals(Params.DEFAULT, Params.fromProperties(m::get, w -> fail(w)), "the example documents the real defaults");
        assertTrue(RevealSettings.enabled(m::get));
    }

    @Test void anOutOfRangeValueFallsBackToTheDefaultsWithAWarning() {
        RevealSettings.setFileValues(RevealSettings.parse(List.of("duration = 99", "speed = 150")).values());
        List<String> warns = new java.util.ArrayList<>();
        Params p = Params.fromProperties(RevealSettings.provider(k -> null), warns::add);
        assertEquals(Params.DEFAULT, p, "the whole set is rejected, as documented");
        assertEquals(1, warns.size());
        RevealSettings.setFileValues(Map.of());
    }
}
