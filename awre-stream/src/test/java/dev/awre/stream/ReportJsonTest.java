package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportJsonTest {
    @Test void scalarsAndOrderAreStable() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1); m.put("a", 2L); m.put("ok", true); m.put("n", null); m.put("x", 1.5);
        assertEquals("{\"b\":1,\"a\":2,\"ok\":true,\"n\":null,\"x\":1.5}", ReportJson.toJson(m));
    }

    @Test void nonFiniteNumbersBecomeNullBecauseJsonHasNoSuchValue() {
        assertEquals("[null,null,null,1.0]", ReportJson.toJson(List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.0)));
    }

    @Test void stringsAreEscapedSoOneReportIsAlwaysOneLine() {
        String nasty = "q\" b\\ nl\n cr\r tab\t bell\u0007 ls\u2028";
        String json = ReportJson.toJson(nasty);
        assertFalse(json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0, "no raw line break");
        assertEquals("\"q\\\" b\\\\ nl\\n cr\\r tab\\t bell\\u0007 ls\\u2028\"", json);
    }

    @Test void pairedSurrogatesPassThroughAndLoneOnesAreEscaped() {
        assertEquals("\"\uD83D\uDE00\"", ReportJson.toJson("\uD83D\uDE00"));
        assertEquals("\"\\ud83d\"", ReportJson.toJson("\uD83D"));
        assertEquals("\"\\ude00x\"", ReportJson.toJson("\uDE00x"));
    }

    @Test void nestedMapsListsAndArraysWork() {
        Map<String, Object> inner = new LinkedHashMap<>(); inner.put("k", List.of(1, 2));
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("p", List.of(inner, "s"));
        outer.put("arr", new Object[]{"a", 3});
        assertEquals("{\"p\":[{\"k\":[1,2]},\"s\"],\"arr\":[\"a\",3]}", ReportJson.toJson(outer));
    }

    @Test void mapKeysAreEscapedToo() {
        assertEquals("{\"a\\\"b\":1}", ReportJson.toJson(Map.of("a\"b", 1)));
    }

    @Test void unknownObjectsUseToString() {
        assertEquals("\"" + java.util.UUID.fromString("00000000-0000-0000-0000-000000000001") + "\"", ReportJson.toJson(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }

    @Test void cyclesAreRejectedNotLoopedForever() {
        List<Object> a = new ArrayList<>(); a.add(a);
        assertThrows(IllegalArgumentException.class, () -> ReportJson.toJson(a));
    }
}
