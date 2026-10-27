package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.awre.stream.StreamConfigCheck.Level;
import org.junit.jupiter.api.Test;

class StreamConfigCheckTest {
    @Test void enabledSyntheticWithRadiusZeroIsTheSilentNothingCaseAndSaysHowToFixIt() {
        var f = StreamConfigCheck.findings(true, false, 0);
        assertEquals(1, f.size());
        assertEquals(Level.WARN, f.get(0).level());
        String t = f.get(0).text();
        assertTrue(t.contains("NO terrain") && t.contains("single handshake test tile"), t);
        assertTrue(t.contains("source: captured") && t.contains("synthetic-chunk-radius above 0"), "names both fixes: " + t);
    }

    @Test void syntheticWithARadiusWarnsThatItIsMadeUpAndReportsItsSize() {
        var f = StreamConfigCheck.findings(true, false, 5);
        assertEquals(Level.WARN, f.get(0).level());
        assertTrue(f.get(0).text().contains("made-up") && f.get(0).text().contains("11x11"), f.get(0).text());
    }

    @Test void capturedIsOnlyANoteAboutLoadedChunksNotAWarning() {
        var f = StreamConfigCheck.findings(true, true, 0);
        assertEquals(1, f.size());
        assertEquals(Level.NOTE, f.get(0).level());
        assertTrue(f.get(0).text().contains("already loaded") && f.get(0).text().contains("never generated"));
    }

    @Test void disabledStreamingWarnsAndMentionsTheRuntimeCommandWhateverTheSource() {
        for (boolean captured : new boolean[]{false, true}) {
            var f = StreamConfigCheck.findings(false, captured, 0);
            assertEquals(Level.WARN, f.get(0).level());
            assertTrue(f.get(0).text().contains("stream.enabled is false") && f.get(0).text().contains("/awre stream on"));
        }
    }

    @Test void aNegativeRadiusIsTreatedLikeZero() {
        assertTrue(StreamConfigCheck.findings(true, false, -3).get(0).text().contains("NO terrain"));
    }
}
