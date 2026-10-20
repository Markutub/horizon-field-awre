package dev.horizonfield.core;

/** Axis-aligned prism of one material, produced by {@link ShellMesher}. Coordinates are world blocks (x1/z1/yHigh exclusive). */
public record ShellPrism(int x0, int z0, int x1, int z1, int yLow, int yHigh, int material, double score) { }
