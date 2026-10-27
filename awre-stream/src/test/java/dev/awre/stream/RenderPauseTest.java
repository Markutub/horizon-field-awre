package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RenderPauseTest {
    @Test void withoutAPropertyItIsNeverPausedAndNeverTouchesTheFilesystem() {
        RenderPause p = RenderPause.fromProperty(null);
        for (int i = 0; i < 1000; i++) assertFalse(p.paused());
        assertFalse(RenderPause.fromProperty("   ").paused(), "blank property means off");
    }

    @Test void followsTheFileButOnlyChecksEveryNthFrame() {
        AtomicBoolean exists = new AtomicBoolean();
        AtomicInteger checks = new AtomicInteger();
        RenderPause p = new RenderPause(() -> { checks.incrementAndGet(); return exists.get(); }, 10);
        assertFalse(p.paused());                       // frame 1 checks (the first call always looks)
        exists.set(true);
        for (int i = 0; i < 9; i++) assertFalse(p.paused(), "still cached until the next check, frame " + (i + 2));
        assertTrue(p.paused(), "frame 11 re-checks and sees the file");
        assertEquals(2, checks.get(), "two filesystem checks in 11 frames, not 11");
        exists.set(false);
        for (int i = 0; i < 9; i++) assertTrue(p.paused());
        assertFalse(p.paused(), "file removed: resumes at the next check");
    }

    @Test void aThrowingCheckNeverPausesRendering() {
        RenderPause p = new RenderPause(() -> { throw new SecurityException("denied"); }, 1);
        for (int i = 0; i < 5; i++) assertFalse(p.paused(), "a broken test hook must not hide the terrain");
    }

    @Test void theIntervalMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new RenderPause(() -> false, 0));
        assertThrows(IllegalArgumentException.class, () -> new RenderPause(() -> false, -3));
    }

    @Test void aRealFilePathWorksEndToEnd() throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("awre-pause", ".flag");
        java.nio.file.Files.delete(f);
        RenderPause p = RenderPause.fromProperty(f.toString());
        assertFalse(p.paused());
        java.nio.file.Files.createFile(f);
        boolean seen = false;
        for (int i = 0; i < RenderPause.DEFAULT_CHECK_EVERY_FRAMES + 1 && !seen; i++) seen = p.paused();
        assertTrue(seen, "the file is noticed within one check interval");
        java.nio.file.Files.delete(f);
    }
}
