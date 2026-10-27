package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientOptionsTest {
    @Test void aValidFileSetsValuesByPropertyNameAndIgnoresCommentsAndCase() {
        var p = ClientOptions.parse(List.of("# c", "", "render = OFF   # no drawing", "MaxDistance=2048", "far = 3000", "vanillaRadius = Client", "iris=assign"));
        assertEquals(List.of(), p.rejected());
        assertEquals(Map.of("awre.render", "off", "awre.view.maxDistance", "2048", "awre.far", "3000", "awre.vanilla.radius", "client", "awre.iris", "assign"), p.values());
    }

    @Test void invalidValuesAreRejectedWithAReasonAndNeverApplied() {
        var p = ClientOptions.parse(List.of("render = maybe", "maxDistance = 99999", "maxDistance = abc", "far = -5", "far = NaN", "vanillaRadius = 7", "iris ="));
        assertEquals(Map.of(), p.values(), "nothing invalid may be applied");
        assertEquals(7, p.rejected().size());
        assertTrue(p.rejected().get(0).contains("expected one of auto, on, off"));
        assertTrue(p.rejected().get(1).contains("64 to 16384"));
    }

    @Test void boundsAreInclusive() {
        assertEquals(Map.of("awre.view.maxDistance", "64"), ClientOptions.parse(List.of("maxDistance = 64")).values());
        assertEquals(Map.of("awre.view.maxDistance", "16384"), ClientOptions.parse(List.of("maxDistance = 16384")).values());
        assertEquals(Map.of(), ClientOptions.parse(List.of("maxDistance = 63.9")).values());
        assertEquals(Map.of("awre.far", "off"), ClientOptions.parse(List.of("far = OFF")).values());
    }

    @Test void unknownKeysAndMalformedLinesAreReportedWithLineNumbers() {
        var p = ClientOptions.parse(List.of("render = on", "bogus = 1", "no equals sign"));
        assertEquals(Map.of("awre.render", "on"), p.values(), "the valid line still loads");
        assertEquals(2, p.rejected().size());
        assertTrue(p.rejected().get(0).startsWith("line 2: unknown option 'bogus'") && p.rejected().get(0).contains("known: render, maxDistance"));
        assertTrue(p.rejected().get(1).startsWith("line 3:"));
    }

    @Test void aDuplicateKeyIsFlaggedAndTheLastValueWins() {
        var p = ClientOptions.parse(List.of("render = on", "render = off"));
        assertEquals("off", p.values().get("awre.render"));
        assertEquals(1, p.rejected().size());
    }

    @Test void jvmPropertyBeatsFileBeatsDefaultAndBlankPropertiesCountAsUnset() {
        Map<String, String> file = Map.of("awre.render", "off");
        assertEquals("on", ClientOptions.resolve(k -> k.equals("awre.render") ? "on" : null, file, "awre.render", "auto"));
        assertEquals("off", ClientOptions.resolve(k -> null, file, "awre.render", "auto"));
        assertEquals("off", ClientOptions.resolve(k -> "  ", file, "awre.render", "auto"), "blank -D is ignored");
        assertEquals("auto", ClientOptions.resolve(k -> null, Map.of(), "awre.render", "auto"));
    }

    @Test void describeShowsWhereEachValueCameFrom() {
        String d = ClientOptions.describe(k -> k.equals("awre.far") ? "off" : null, Map.of("awre.render", "off"));
        assertTrue(d.contains("render=off(file)") && d.contains("far=off(jvm)") && d.contains("maxDistance=16384(default)"), d);
    }

    @Test void theGeneratedExampleIsAllCommentsAndParsesToNothingButUncommentingWorks() {
        String ex = ClientOptions.example();
        var none = ClientOptions.parse(ex.lines().toList());
        assertEquals(Map.of(), none.values()); assertEquals(List.of(), none.rejected());
        for (ClientOptions.Option o : ClientOptions.OPTIONS) assertTrue(ex.contains("# " + o.key() + " = " + o.def()), "documents " + o.key());
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (ClientOptions.Option o : ClientOptions.OPTIONS) keys.add(o.key() + " = ");
        // a user uncomments only the option lines ("# key = value"), never the help text above them
        var all = ClientOptions.parse(ex.lines().map(l -> l.startsWith("# ") && keys.stream().anyMatch(k -> l.startsWith("# " + k)) ? l.substring(2) : l).toList());
        assertEquals(List.of(), all.rejected(), "every documented default is itself a valid value");
        assertEquals(ClientOptions.OPTIONS.size(), all.values().size());
    }

    @Test void everyOptionDefaultPassesItsOwnValidator() {
        for (ClientOptions.Option o : ClientOptions.OPTIONS) assertEquals(null, o.check().apply(o.def()), o.key());
        assertFalse(ClientOptions.OPTIONS.isEmpty());
    }
}
