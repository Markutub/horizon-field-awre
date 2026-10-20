package dev.horizonfield.core;

/**
 * @param eyeX/eyeY/eyeZ   viewer eye position in blocks
 * @param minRadius        start of the sweep (blocks); typically just beyond real chunks
 * @param maxRadius        end of the sweep (blocks)
 * @param azimuthBins      number of rays over 360 degrees
 * @param slackBlocks      occluders are lowered by this much so small viewer movements do not reveal un-sent terrain
 */
public record VisibilityParams(double eyeX, double eyeY, double eyeZ, double minRadius, double maxRadius,
                               int azimuthBins, double slackBlocks) {
    public VisibilityParams {
        if (minRadius <= 0 || maxRadius <= minRadius) throw new IllegalArgumentException("radius range");
        if (azimuthBins < 8) throw new IllegalArgumentException("azimuthBins");
        if (slackBlocks < 0) throw new IllegalArgumentException("slackBlocks");
    }
}
