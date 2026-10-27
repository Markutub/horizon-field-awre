package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonlLogTest {
    private static Path dir() throws Exception { return Files.createTempDirectory("awre-jsonl"); }

    @Test void appendsOneLinePerCallAndCreatesMissingDirectories() throws Exception {
        Path f = dir().resolve("a/b/report.jsonl");
        JsonlLog log = new JsonlLog(f, 1 << 20);
        log.append("{\"n\":1}"); log.append("{\"n\":2}");
        assertEquals(List.of("{\"n\":1}", "{\"n\":2}"), Files.readAllLines(f));
    }

    @Test void rotatesToExactlyOneBackupAndKeepsNewestLines() throws Exception {
        Path f = dir().resolve("r.jsonl");
        JsonlLog log = new JsonlLog(f, 1024);
        String line = "x".repeat(300);                       // 301 bytes with the newline: 3 fit, the 4th rotates
        for (int i = 0; i < 4; i++) log.append(line + i);
        Path bak = f.resolveSibling("r.jsonl.1");
        assertTrue(Files.exists(bak), "backup created");
        assertEquals(3, Files.readAllLines(bak).size());
        assertEquals(List.of(line + 3), Files.readAllLines(f));
        for (int i = 4; i < 9; i++) log.append(line + i);   // two more rotations
        assertFalse(Files.exists(f.resolveSibling("r.jsonl.2")), "only ONE backup generation");
        assertTrue(Files.size(f) + Files.size(bak) <= 2048, "disk use bounded by about 2 x max");
        assertEquals(line + 8, Files.readAllLines(f).get(Files.readAllLines(f).size() - 1), "the newest line is never lost");
    }

    @Test void rejectsLinesThatWouldBreakTheFormatOrTheLimit() throws Exception {
        JsonlLog log = new JsonlLog(dir().resolve("x.jsonl"), 1024);
        assertThrows(IllegalArgumentException.class, () -> log.append("a\nb"));
        assertThrows(IllegalArgumentException.class, () -> log.append("a\rb"));
        assertThrows(IllegalArgumentException.class, () -> log.append("y".repeat(2000)));
        assertThrows(IllegalArgumentException.class, () -> new JsonlLog(dir().resolve("z"), 10));
    }

    @Test void utf8IsWrittenAsUtf8() throws Exception {
        Path f = dir().resolve("u.jsonl");
        new JsonlLog(f, 1 << 20).append("{\"s\":\"h\u00e9llo \u4e16\u754c\"}");
        assertEquals("{\"s\":\"h\u00e9llo \u4e16\u754c\"}", Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8).get(0));
    }
}
