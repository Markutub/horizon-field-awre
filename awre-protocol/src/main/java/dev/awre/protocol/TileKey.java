package dev.awre.protocol;

/** Stable tile identity: world + dimension + position + level. */
public record TileKey(long worldMsb, long worldLsb, String dimension, int tileX, int tileZ, int lod) { }
