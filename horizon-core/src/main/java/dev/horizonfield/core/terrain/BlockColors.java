package dev.horizonfield.core.terrain;

import java.util.HashMap;
import java.util.Map;

/**
 * Map-style colours (opaque ARGB) for common block keys. APPROXIMATE: values are rounded from memory of Minecraft's map colours,
 * not read from the game's assets. Unknown keys get a stable hash colour, so modded blocks stay distinguishable but not realistic.
 */
public final class BlockColors {
    private static final Map<String, Integer> TABLE = new HashMap<>();
    private static void put(String k, int rgb) { TABLE.put("minecraft:" + k, 0xFF000000 | rgb); }
    static {
        put("grass_block", 0x7FB238); put("moss_block", 0x667F33); put("podzol", 0x815631); put("mycelium", 0x6E6A7A);
        put("dirt", 0x976D4D); put("coarse_dirt", 0x976D4D); put("rooted_dirt", 0x976D4D); put("mud", 0x3C3A40); put("clay", 0xA4A8B8);
        put("sand", 0xF7E9A3); put("red_sand", 0xD87F33); put("sandstone", 0xE4D9A0); put("gravel", 0x8E8A86);
        put("stone", 0x707070); put("deepslate", 0x4A4A4F); put("cobblestone", 0x797979); put("andesite", 0x888888); put("diorite", 0xBFBFBF); put("granite", 0x9A6B5B);
        put("tuff", 0x6C6D64); put("calcite", 0xE0E0DA); put("dripstone_block", 0x7A6048); put("bedrock", 0x2E2E2E); put("netherrack", 0x6F3535);
        put("terracotta", 0x985E43); put("snow", 0xFFFFFF); put("snow_block", 0xFFFFFF); put("powder_snow", 0xFFFFFF); put("ice", 0xA0A0FF); put("packed_ice", 0x8FA8E8); put("blue_ice", 0x7F9CF0);
        put("water", 0x4040FF); put("lava", 0xFF6A00); put("oak_leaves", 0x2F7A1F); put("birch_leaves", 0x6B9B45); put("spruce_leaves", 0x3F5F3A);
        put("jungle_leaves", 0x2E8B1A); put("acacia_leaves", 0x5C8F2A); put("dark_oak_leaves", 0x2A5F20); put("oak_log", 0x8F7748); put("spruce_log", 0x4A3626);
        put("birch_log", 0xD7D3C0); put("oak_planks", 0xA58B52); put("gold_block", 0xF9D84A); put("iron_block", 0xD8D8D8); put("diamond_block", 0x4CD8D0);
        put("obsidian", 0x15121C); put("end_stone", 0xDDE0A5);
    }

    private static volatile ColorOverrides overrides = ColorOverrides.EMPTY;

    private BlockColors() { }

    /** Replaces the active overrides (never null). Rules apply before the built-in table; air is never overridden. */
    public static void setOverrides(ColorOverrides o) { overrides = o == null ? ColorOverrides.EMPTY : o; }

    /** Opaque ARGB for a block key; air and unknown-air give 0 (fully transparent). */
    public static int argb(String key) {
        if (key == null || key.endsWith(":air") || key.endsWith("_air")) return 0;
        Integer o = overrides.lookup(key);
        if (o != null) return o;
        Integer c = TABLE.get(key);
        if (c != null) return c;
        int h = key.hashCode() * 0x9E3779B1;
        int r = 80 + ((h >>> 16) & 0x7F), g = 80 + ((h >>> 8) & 0x7F), b = 80 + (h & 0x7F);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
