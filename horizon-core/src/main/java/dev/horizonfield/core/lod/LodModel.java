package dev.horizonfield.core.lod;

/**
 * Screen-space-error model for terrain LOD (the classic geometric-error / pixel-tolerance argument used by CDLOD and
 * chunked LOD; see docs/AWRE_G1_FEATURES.md section 2).
 * <pre>
 *   level L samples every 2^L blocks, so its height error is eps_L = kappa * (2^L - 1) blocks   (kappa = terrain roughness, ASSUMPTION)
 *   a pixel of error at distance d:   p = eps * H / (2 d tan(fov/2))
 *   require p <= tau  =>  d >= eps * K,   K = H / (2 tau tan(fov/2))
 *   so a level-L tile is acceptable beyond  d_L = max(kappa (2^L - 1) K,  minRangeRatio * S * 2^L)
 * </pre>
 * The second term keeps neighbouring level ranges from collapsing into each other when kappa is tiny.
 * All fields are inputs, not measurements: kappa in particular must be calibrated on real terrain (RealTileReport LOD-error lines).
 */
public record LodModel(double fovDegrees, int viewportHeightPx, double tolerancePx, double kappa,
                       int tileSamples, int maxLod, double hysteresis, double eyeHeight, double minRangeRatio) {

    public static LodModel defaults() { return new LodModel(70.0, 1080, 2.0, 0.5, 16, 6, 0.15, 0.0, 1.0); }

    public LodModel {
        if (fovDegrees < 10 || fovDegrees > 170) throw new IllegalArgumentException("fov");
        if (viewportHeightPx < 120) throw new IllegalArgumentException("viewport");
        if (tolerancePx <= 0 || kappa <= 0) throw new IllegalArgumentException("tolerance/kappa");
        if (tileSamples < 2 || Integer.bitCount(tileSamples) != 1) throw new IllegalArgumentException("tileSamples must be a power of two");
        if (maxLod < 0 || maxLod > 12) throw new IllegalArgumentException("maxLod");
        if (hysteresis < 0 || hysteresis >= 0.9) throw new IllegalArgumentException("hysteresis");
        if (eyeHeight < 0 || minRangeRatio <= 0) throw new IllegalArgumentException("eye/minRange");
    }

    /** Pixels of error per block of height error at distance 1 block (K * tau = H / (2 tan(fov/2))). */
    public double pixelsPerBlockAtUnitDistance() { return viewportHeightPx / (2.0 * Math.tan(Math.toRadians(fovDegrees) / 2.0)); }

    public double kFactor() { return pixelsPerBlockAtUnitDistance() / tolerancePx; }

    public double tileSize(int lod) { return (double) tileSamples * (1L << lod); }

    public double errorBlocks(int lod) { return kappa * (double) ((1L << lod) - 1); }

    /** Projected error in pixels of a level-lod tile seen from distance dist (blocks). */
    public double projectedPx(int lod, double dist) { return errorBlocks(lod) * pixelsPerBlockAtUnitDistance() / Math.max(dist, 1e-6); }

    /** A tile of this level is acceptable (<= tolerance) at or beyond this distance; closer than this it is split. */
    public double splitDistance(int lod) {
        return Math.max(errorBlocks(lod) * kFactor(), minRangeRatio * tileSize(lod)) * (lod == 0 ? 0.0 : 1.0);
    }
}
