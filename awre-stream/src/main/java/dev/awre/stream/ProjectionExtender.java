package dev.awre.stream;

/**
 * Far-plane math for the AWRE terrain pass. Pure Java (floats, no Minecraft/JOML types).
 *
 * <p>Why: the game's level projection has a far plane tied to the render distance (API dump of 1.21.11: {@code GameRenderer#getDepthFar()}),
 * so terrain beyond it is clipped however far AWRE streams it. The renderer rebuilds the SAME projection with a larger far plane
 * by replacing only the two depth entries of the matrix; x/y scale (FOV, aspect) is untouched, so AWRE terrain lines up with vanilla.
 * The substitution is only valid for a standard OpenGL perspective matrix, which {@link #extendFar} verifies instead of assuming
 * (a zero-to-one or reversed-Z matrix is refused and the caller keeps vanilla's projection).
 *
 * <p>JOML naming mCR = column C, row R: m22 = -(f+n)/(f-n), m32 = -2fn/(f-n), m23 = -1.
 *
 * <p>Known cost, by construction: depth values from two different far planes are not identical, so AWRE fragments compare slightly
 * wrongly against vanilla depth near the seam. With near n and far planes f1 (vanilla) and f2 (AWRE) a fragment at distance dA behind
 * vanilla geometry at dV is wrongly drawn over it when 1/dV - 1/dA < 1/f1 - 1/f2, i.e. only within roughly (1 + n-independent) 33% of the
 * vanilla edge when f1 = 4R. See docs/COMPAT_PLAN.md 1.1.
 */
public final class ProjectionExtender {
    private ProjectionExtender() { }

    /** The two depth entries of a perspective matrix. */
    public record Z(float m22, float m32) { }

    /** Largest far plane the renderer will ask for (float depth precision is useless beyond it anyway). */
    public static final float MAX_FAR = 200_000f;

    /**
     * The near plane a standard OpenGL perspective matrix encodes: {@code m32 / (m22 - 1)}. (The far plane, {@code m32 / (m22 + 1)}, is
     * numerically useless: m22 is within 1e-4 of -1 for any far above ~500, so float rounding swamps it. Only near is checked.)
     */
    public static float impliedNear(float m22, float m32) { return m32 / (m22 - 1f); }

    /**
     * @return the (m22, m32) of the same perspective with far = {@code newFar}, or null if the inputs are not a standard OpenGL
     *         -z perspective matrix whose near plane is {@code near} (a zero-to-one or reversed-Z matrix implies a different near),
     *         or the planes are invalid
     */
    public static Z extendFar(float m22, float m32, float m23, float near, float newFar) {
        if (!(near > 0f) || !(newFar > near) || !Float.isFinite(newFar) || !Float.isFinite(m22) || !Float.isFinite(m32)) return null;
        if (Math.abs(m23 + 1f) > 1e-6f) return null;                                   // not a -z perspective divide
        float implied = impliedNear(m22, m32);
        if (!(Math.abs(implied - near) <= 1e-2f * near)) return null;                  // zero-to-one depth implies ~near/2
        float far = Math.min(newFar, MAX_FAR);
        return new Z(-(far + near) / (far - near), -2f * far * near / (far - near));
    }

    /** NDC depth (-1 at the near plane, +1 at the far plane) of a point at view distance {@code d} in front of the camera. */
    public static float ndcDepth(Z z, float d) { return (-z.m22() * d + z.m32()) / d; }

    /**
     * Far plane for the AWRE pass: enough to cover the streamed distance (with slack for height and diagonals), never below vanilla's.
     * @param viewMaxDistance planner maxDistance in blocks (horizontal)
     */
    public static float chooseFar(double viewMaxDistance, float vanillaFar) {
        double want = viewMaxDistance * 1.1 + 512.0;
        return (float) Math.min(MAX_FAR, Math.max(vanillaFar, want));
    }

    /**
     * Parses {@code -Dawre.far}: "auto" (default, also for null/garbage) = NaN, "off" = 0 (keep vanilla's projection), otherwise an
     * explicit far plane in blocks (must exceed 64; anything else = auto).
     */
    public static float parseFarSetting(String s) {
        if (s == null) return Float.NaN;
        String t = s.strip().toLowerCase(java.util.Locale.ROOT);
        if (t.equals("off") || t.equals("false") || t.equals("0")) return 0f;
        try {
            float v = Float.parseFloat(t);
            return v > 64f && Float.isFinite(v) ? Math.min(v, MAX_FAR) : Float.NaN;
        } catch (NumberFormatException e) { return Float.NaN; }
    }
}
