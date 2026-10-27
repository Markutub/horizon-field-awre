package dev.awre.stream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * TEST HOOK for the CI pixel check (tools/ci/pixel-check.sh): while a flag file exists the client skips drawing AWRE terrain, so the SAME
 * client session yields an AWRE-on frame and an AWRE-off control frame seconds apart (time of day, camera and weather cannot differ).
 * Enabled only by {@code -Dawre.test.pauseFile=<path>}; without it {@link #paused()} is a constant false with no filesystem access.
 * The file is polled every {@link #DEFAULT_CHECK_EVERY_FRAMES} frames (not every frame). Any error while checking means "not paused": a broken
 * hook must never hide terrain from a player. Pure Java, render thread only (not thread-safe by design).
 */
public final class RenderPause {
    public static final int DEFAULT_CHECK_EVERY_FRAMES = 15;
    private static final RenderPause NEVER = new RenderPause(() -> false, Integer.MAX_VALUE);

    private final BooleanSupplier flagExists;
    private final int every;
    private int sinceCheck;
    private boolean checkedOnce, cached;

    public RenderPause(BooleanSupplier flagExists, int checkEveryFrames) {
        if (checkEveryFrames <= 0) throw new IllegalArgumentException("checkEveryFrames must be > 0: " + checkEveryFrames);
        this.flagExists = flagExists;
        this.every = checkEveryFrames;
    }

    /** {@code null} or blank gives the inert instance; anything else is the path of the flag file. */
    public static RenderPause fromProperty(String path) {
        if (path == null || path.isBlank()) return NEVER;
        Path p = Path.of(path.trim());
        return new RenderPause(() -> Files.exists(p), DEFAULT_CHECK_EVERY_FRAMES);
    }

    /** Call once per rendered frame. True = skip drawing AWRE terrain this frame. */
    public boolean paused() {
        if (this == NEVER) return false;
        if (!checkedOnce || ++sinceCheck >= every) {
            checkedOnce = true; sinceCheck = 0;
            try { cached = flagExists.getAsBoolean(); } catch (RuntimeException e) { cached = false; }
        }
        return cached;
    }
}
