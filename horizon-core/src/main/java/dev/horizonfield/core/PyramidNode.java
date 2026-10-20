package dev.horizonfield.core;

/**
 * One node of the max/min height pyramid. {@code minY}/{@code maxY} are surface block Y values over the node footprint.
 * {@code complete} is true only if every chunk in the footprint is known; only complete nodes may act as occluders.
 */
public record PyramidNode(short minY, short maxY, short material, Confidence confidence, boolean complete) { }
