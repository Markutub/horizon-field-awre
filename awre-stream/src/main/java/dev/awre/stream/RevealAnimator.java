package dev.awre.stream;

import dev.awre.protocol.TileKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The "terrain rises out of the ground, goes ghost-grey while up, settles back to its real colour" reveal effect for distant tiles.
 *
 * <p>This class is the single source of truth: the formulas below are mirrored 1:1 by {@code awre_terrain.vsh/.fsh} (the contract test
 * {@code RevealShaderContractTest} fails when the packing or constants drift), the parameters are handed to the shader as uniforms
 * ({@link Params#toUniform()}), and the offline preview tool renders frames with these same functions.
 *
 * <h3>Model</h3>
 * A reveal session starts when the first tile of a world is drawn: the player's x/z at that moment is the wave origin and the clock
 * starts ({@code revealAge}). A vertex at horizontal distance {@code d} from the origin starts moving at
 * {@code min(d / speed, maxDelay)} seconds (the ring spreads out like chunk loading) but never before its tile became drawable
 * ({@code tileAge}); {@code tau = min(tileAge, revealAge - delay)} is the vertex's own clock. {@code tau < 0}: not revealed yet (hidden).
 * With {@code s = tau / duration} in [0,1]: the terrain emerges from {@code depth} below its true height, lifts by {@code peak}, settles
 * to 0 with a small damped ripple, and the colour is mixed towards grey by {@code greyPeak * sin^2(pi s)}, i.e. grey exactly while it
 * is up and back to the true colour when it is down.
 *
 * <p>Only the FIRST reveal of an area animates. Re-uploads of a tile (new revision) keep their clock, and a tile whose whole footprint
 * was already revealed (an LOD swap over visible ground) starts finished, so refinement never replays the effect.
 *
 * <p>Render thread only. Memory is bounded ({@link #MAX_TRACKED_TILES}, {@link #MAX_REVEALED_CELLS}).
 */
public final class RevealAnimator {
    /** Seconds used for "finished": larger than any {@code duration}, so the shader's {@code tau} is far past the end (offset 0, no grey). */
    public static final float DONE = 10_000f;
    /** Grey target = luma * GREY_LUMA_WEIGHT + GREY_LIFT (a light, ghostly grey rather than a dark one). Mirrored in the fragment shader. */
    public static final double GREY_LUMA_WEIGHT = 0.55, GREY_LIFT = 0.30;
    /**
     * Revealed-area bookkeeping. A cell is the edge of the SMALLEST tile (LOD 0 = one 16-block chunk column); tiles are aligned to their own
     * size, so a tile covers a whole number of cells and two tiles never share a cell unless one contains the other. (A coarser cell would
     * wrongly mark a tile's neighbours as already revealed; the preview tool caught exactly that.) Tiles covering more than
     * MAX_CELLS_PER_TILE cells are remembered as rectangles instead.
     */
    public static final double CELL_SIZE = 16;
    public static final int MAX_CELLS_PER_TILE = 1024, MAX_REVEALED_CELLS = 262_144, MAX_TRACKED_TILES = 65_536, MAX_COARSE_RECTS = 8192;

    /** Effect parameters. Defaults are a starting point to be tuned with {@code tools/preview/run-reveal-preview.sh} and in game. */
    public record Params(double speed, double maxDelay, double duration, double peak, double depth, double emerge,
                         double greyPeak, double rippleAmp, double rippleCycles) {
        /** speed 120 blocks/s; far terrain waits at most 6 s; each column's motion lasts 1.6 s; 6 blocks up, 10 blocks emerge depth. */
        public static final Params DEFAULT = new Params(120, 6, 1.6, 6, 10, 0.3, 0.85, 1.2, 2);

        public Params {
            require(speed >= 1 && speed <= 4000, "speed (blocks/s) in [1, 4000]");
            require(maxDelay >= 0 && maxDelay <= 60, "maxDelay (s) in [0, 60]");
            require(duration >= 0.2 && duration <= 10, "duration (s) in [0.2, 10]");
            require(peak >= 0 && peak <= 64, "peak (blocks) in [0, 64]");
            require(depth >= 0 && depth <= 64, "depth (blocks) in [0, 64]");
            require(emerge >= 0 && emerge <= 0.9, "emerge (fraction) in [0, 0.9]");
            require(greyPeak >= 0 && greyPeak <= 1, "greyPeak in [0, 1]");
            require(rippleAmp >= 0 && rippleAmp <= 16, "rippleAmp (blocks) in [0, 16]");
            require(rippleCycles >= 0 && rippleCycles <= 6, "rippleCycles in [0, 6]");
        }

        private static void require(boolean ok, String what) { if (!ok) throw new IllegalArgumentException("reveal parameter out of range: " + what); }

        /**
         * Shader packing, column-major 4x4 for {@code TextureMat}: column 0 = (speed, maxDelay, duration, peak),
         * column 1 = (depth, emerge, greyPeak, rippleAmp), column 2 = (rippleCycles, 0, 0, 0), column 3 unused.
         * The vertex shader reads {@code TextureMat[column][row]}; changing this order requires changing the shader (contract test).
         */
        public float[] toUniform() {
            return new float[]{(float) speed, (float) maxDelay, (float) duration, (float) peak,
                    (float) depth, (float) emerge, (float) greyPeak, (float) rippleAmp,
                    (float) rippleCycles, 0, 0, 0,
                    0, 0, 0, 0};
        }

        /** Overrides from {@code awre.reveal.<name>} style keys; unknown/invalid values keep the default and are reported through {@code warn}. */
        public static Params fromProperties(Function<String, String> get, java.util.function.Consumer<String> warn) {
            Params d = DEFAULT;
            double[] v = {d.speed, d.maxDelay, d.duration, d.peak, d.depth, d.emerge, d.greyPeak, d.rippleAmp, d.rippleCycles};
            String[] names = {"speed", "maxDelay", "duration", "peak", "depth", "emerge", "greyPeak", "rippleAmp", "rippleCycles"};
            for (int i = 0; i < names.length; i++) {
                String raw = get.apply("awre.reveal." + names[i]);
                if (raw == null || raw.isBlank()) continue;
                try { v[i] = Double.parseDouble(raw.trim()); } catch (NumberFormatException e) { warn.accept("ignoring awre.reveal." + names[i] + "=" + raw + " (not a number)"); }
            }
            try { return new Params(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]); }
            catch (IllegalArgumentException e) { warn.accept(e.getMessage() + "; using the defaults"); return DEFAULT; }
        }
    }

    /** What one draw call needs: wave origin relative to the camera (x, z) and the two clocks in seconds. */
    public record Uniform(float originRelX, float originRelZ, float revealAge, float tileAge) {
        public static final Uniform NONE = new Uniform(0, 0, DONE, DONE);
        public boolean isNone() { return tileAge >= DONE; }
    }

    // ---- reference formulas (the shader mirrors these) ----------------------------------------------------------------------

    /** Seconds since this vertex started moving; negative = not revealed yet. */
    public static double tau(Params p, double revealAge, double tileAge, double dist) {
        return Math.min(tileAge, revealAge - Math.min(dist / p.speed(), p.maxDelay()));
    }

    /** Vertical offset in blocks at s = tau/duration (0 outside [0,1): hidden before, settled after). */
    public static double lift(Params p, double s) {
        if (!(s >= 0 && s < 1)) return 0;
        double env = Math.sin(Math.PI * s); env *= env;
        double e = p.emerge() > 0 ? clamp01(s / p.emerge()) : 1;
        e = e * e * (3 - 2 * e);
        double r = 1 - s;
        return p.peak() * env - p.depth() * (1 - e) + p.rippleAmp() * Math.sin(2 * Math.PI * p.rippleCycles() * s) * r * r;
    }

    /** Grey mix factor in [0, greyPeak]: sin^2 so it is grey exactly while the terrain is up and back to normal at both ends. */
    public static double grey(Params p, double s) {
        if (!(s >= 0 && s < 1)) return 0;
        double env = Math.sin(Math.PI * s);
        return p.greyPeak() * env * env;
    }

    /** The fragment colour: {@code mix(c, luma(c) * GREY_LUMA_WEIGHT + GREY_LIFT, grey)}, per channel in 0..1. */
    public static double[] greyMix(double r, double g, double b, double grey) {
        double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b, target = luma * GREY_LUMA_WEIGHT + GREY_LIFT;
        return new double[]{r + (target - r) * grey, g + (target - g) * grey, b + (target - b) * grey};
    }

    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    // ---- state ---------------------------------------------------------------------------------------------------------------

    private final Params params;
    private final boolean enabled;
    private boolean armed;
    private double originX, originZ;
    private long t0Nanos;
    private final Map<TileKey, Long> firstDrawn = new HashMap<>();     // value: nanoTime of first draw, or Long.MIN_VALUE = starts finished
    private final Set<Long> revealedCells = new HashSet<>();
    private final java.util.List<double[]> coarseRects = new java.util.ArrayList<>();   // {minX, minZ, size} of revealed tiles too big for cells

    public RevealAnimator(Params params, boolean enabled) { this.params = params; this.enabled = enabled; }

    public Params params() { return params; }
    public boolean enabled() { return enabled; }
    public boolean armed() { return armed; }
    public int trackedTiles() { return firstDrawn.size(); }
    public int revealedCells() { return revealedCells.size(); }
    public int coarseRects() { return coarseRects.size(); }

    /** Forget everything (dimension change, disconnect, shutdown): the next first tile starts a new reveal session. */
    public void reset() { armed = false; firstDrawn.clear(); revealedCells.clear(); coarseRects.clear(); }

    /**
     * The uniform for drawing {@code key} this frame. The first call for a key starts (or skips) its reveal; later calls only read the clocks.
     *
     * @param minX/minZ world position of the tile's minimum corner, {@code size} its edge in blocks (footprint for the revealed-area test)
     * @param camX/camZ camera (the player) in world coordinates; {@code nowNanos} a monotonic clock ({@code System.nanoTime()})
     */
    public Uniform forDraw(TileKey key, double minX, double minZ, double size, double camX, double camZ, long nowNanos) {
        if (!enabled) return Uniform.NONE;
        if (!armed) { armed = true; originX = camX; originZ = camZ; t0Nanos = nowNanos; }
        Long seen = firstDrawn.get(key);
        if (seen == null) {
            if (firstDrawn.size() >= MAX_TRACKED_TILES || revealedCells.size() >= MAX_REVEALED_CELLS || coarseRects.size() >= MAX_COARSE_RECTS) { firstDrawn.clear(); revealedCells.clear(); coarseRects.clear(); }
            seen = coveredByRevealed(minX, minZ, size) ? Long.MIN_VALUE : nowNanos;
            firstDrawn.put(key, seen);
            if (seen != Long.MIN_VALUE) markRevealed(minX, minZ, size);
        }
        if (seen == Long.MIN_VALUE) return Uniform.NONE;
        double tileAge = (nowNanos - seen) / 1e9, revealAge = (nowNanos - t0Nanos) / 1e9;
        if (tileAge >= params.duration() && revealAge >= params.maxDelay() + params.duration()) return Uniform.NONE;   // provably past the end for every vertex
        return new Uniform((float) (originX - camX), (float) (originZ - camZ), (float) revealAge, (float) tileAge);
    }

    private boolean coveredByRevealed(double minX, double minZ, double size) {
        for (double[] r : coarseRects)                                            // inside an already revealed coarse tile (LOD refinement)
            if (minX >= r[0] && minZ >= r[1] && minX + size <= r[0] + r[2] && minZ + size <= r[1] + r[2]) return true;
        int x0 = cell(minX), x1 = cell(minX + size - 1e-6), z0 = cell(minZ), z1 = cell(minZ + size - 1e-6);
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > MAX_CELLS_PER_TILE) return false;    // huge tile: only covered by a coarser rectangle
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) if (!revealedCells.contains(pack(x, z))) return false;
        return true;
    }

    private void markRevealed(double minX, double minZ, double size) {
        int x0 = cell(minX), x1 = cell(minX + size - 1e-6), z0 = cell(minZ), z1 = cell(minZ + size - 1e-6);
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > MAX_CELLS_PER_TILE) { coarseRects.add(new double[]{minX, minZ, size}); return; }
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) revealedCells.add(pack(x, z));
    }

    private static int cell(double v) { return (int) Math.floor(v / CELL_SIZE); }
    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }
}
