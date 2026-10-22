package dev.horizonfield.core.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Biome-dependent colours for the blocks Minecraft tints (grass, foliage, water). Pure Java.
 *
 * <p>Transport trick: no tile-format or protocol change. For a tintable block the server puts the biome INTO the palette key:
 * {@code minecraft:grass_block/biome/minecraft:desert} ({@link #tag}). The palette, the tile codecs, persistence and the client
 * store treat it as an ordinary key; {@link BlockColors#argb} splits it again and asks this class for the colour. Keys use only
 * characters PaletteUpdate accepts, and a tag that would not fit (96 chars / bad characters) silently falls back to the plain key.
 *
 * <p>The built-in colours are APPROXIMATE values recalled from memory, not read from the game's assets. Biomes this table does not
 * know (worldgen datapacks and plugins) get a keyword heuristic ("desert", "swamp", "frozen"...) and finally the plains colours.
 * Exact colours: {@link #setOverrides} with lines {@code biome_id = grass:#RRGGBB foliage:#RRGGBB water:#RRGGBB} (any subset).
 */
public final class BiomeTint {
    public static final String SEP = "/biome/";
    public static final int MAX_KEY_CHARS = 96;      // = Awre.MAX_PALETTE_KEY_CHARS (core does not depend on the protocol module)

    public enum Kind { NONE, GRASS, FOLIAGE, WATER }

    private BiomeTint() { }

    private record Triple(int grass, int foliage, int water) { }
    private static final Triple PLAINS = new Triple(0x91BD59, 0x77AB2F, 0x3F76E4);
    private static final Map<String, Triple> TABLE = new HashMap<>();
    private static void put(int grass, int foliage, int water, String... names) { Triple t = new Triple(grass, foliage, water); for (String n : names) TABLE.put("minecraft:" + n, t); }
    static {
        put(0x91BD59, 0x77AB2F, 0x3F76E4, "plains", "sunflower_plains", "beach", "river");
        put(0x79C05A, 0x59AE30, 0x3F76E4, "forest", "flower_forest");
        put(0x88BB67, 0x6BA941, 0x3F76E4, "birch_forest", "old_growth_birch_forest");
        put(0x507A32, 0x59AE30, 0x3F76E4, "dark_forest");
        put(0x86B783, 0x68A464, 0x3D57D6, "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga");
        put(0x80B497, 0x60A17B, 0x3D57D6, "snowy_plains", "ice_spikes", "snowy_taiga", "grove", "snowy_slopes", "frozen_peaks", "jagged_peaks");
        put(0x83B593, 0x64A278, 0x3D57D6, "snowy_beach");
        put(0xBFB755, 0xAEA42A, 0x3F76E4, "desert", "savanna", "savanna_plateau", "windswept_savanna");
        put(0x90814D, 0x9E814D, 0x3F76E4, "badlands", "eroded_badlands", "wooded_badlands");
        put(0x59C93C, 0x30BB0B, 0x3F76E4, "jungle", "sparse_jungle", "bamboo_jungle");
        put(0x6A7039, 0x6A7039, 0x617B64, "swamp");
        put(0x8DB127, 0x8DB127, 0x3A7A6A, "mangrove_swamp");
        put(0x55C93F, 0x2BBB0F, 0x3F76E4, "mushroom_fields");
        put(0x8AB689, 0x6DA36B, 0x3F76E4, "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "stony_shore", "stony_peaks");
        put(0x83BB6D, 0x63A948, 0x0E4ECF, "meadow");
        put(0xB6DB61, 0xB6DB61, 0x5DB7EF, "cherry_grove");
        put(0x8EB971, 0x71A74D, 0x3F76E4, "ocean", "deep_ocean");
        put(0x8EB971, 0x71A74D, 0x45ADF2, "lukewarm_ocean", "deep_lukewarm_ocean");
        put(0x8EB971, 0x71A74D, 0x43D5EE, "warm_ocean");
        put(0x8EB971, 0x71A74D, 0x3D57D6, "cold_ocean", "deep_cold_ocean");
        put(0x80B497, 0x60A17B, 0x3938C9, "frozen_ocean", "deep_frozen_ocean", "frozen_river");
    }

    private record Custom(int grass, int foliage, int water) { }   // -1 = not overridden
    private static volatile Map<String, Custom> overrides = Map.of();

    /** Replaces the user overrides (null clears). */
    public static void setOverrides(Map<String, int[]> parsed) {
        Map<String, Custom> m = new HashMap<>();
        if (parsed != null) for (var e : parsed.entrySet()) m.put(e.getKey(), new Custom(e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        overrides = Map.copyOf(m);
    }

    /** Result of {@link #parse}: overrides as biome id -> {grass, foliage, water} ARGB (-1 = not given) and the rejected lines. */
    public record Parsed(Map<String, int[]> overrides, List<String> rejected) { }

    public static Parsed parse(List<String> lines) {
        Map<String, int[]> out = new HashMap<>();
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (String raw : lines) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) { bad.add("line " + n + ": missing '='"); continue; }
            String id = line.substring(0, eq).strip();
            if (id.isEmpty() || id.indexOf(' ') >= 0 || id.indexOf(':') < 0) { bad.add("line " + n + ": bad biome id"); continue; }
            int[] v = {-1, -1, -1};
            boolean ok = true;
            for (String part : line.substring(eq + 1).trim().split("[\\s,]+")) {
                if (part.isEmpty()) continue;
                int c = part.indexOf(':');
                String kind = c < 0 ? "" : part.substring(0, c), val = c < 0 ? "" : part.substring(c + 1);
                int slot = switch (kind) { case "grass" -> 0; case "foliage" -> 1; case "water" -> 2; default -> -1; };
                if (slot < 0 || val.length() != 7 || val.charAt(0) != '#') { ok = false; break; }
                try { v[slot] = 0xFF000000 | Integer.parseInt(val.substring(1), 16); } catch (NumberFormatException e) { ok = false; break; }
            }
            if (!ok || (v[0] == -1 && v[1] == -1 && v[2] == -1)) { bad.add("line " + n + ": expected grass:#RRGGBB foliage:#RRGGBB water:#RRGGBB"); continue; }
            out.put(id, v);
        }
        return new Parsed(out, List.copyOf(bad));
    }

    // ---- keys ----

    public static Kind kindOf(String baseKey) {
        if (baseKey == null) return Kind.NONE;
        return switch (baseKey) {
            case "minecraft:grass_block", "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern", "minecraft:grass" -> Kind.GRASS;
            case "minecraft:oak_leaves", "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves", "minecraft:mangrove_leaves", "minecraft:vine" -> Kind.FOLIAGE;
            case "minecraft:water" -> Kind.WATER;
            default -> Kind.NONE;
        };
    }

    public static boolean tintable(String baseKey) { return kindOf(baseKey) != Kind.NONE; }

    /** {@code base + SEP + biome} if the block is tintable, the biome is known and the result is a valid palette key; else {@code base}. */
    public static String tag(String baseKey, String biomeKey) {
        if (biomeKey == null || !tintable(baseKey)) return baseKey;
        String k = baseKey + SEP + biomeKey;
        if (k.length() > MAX_KEY_CHARS) return baseKey;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' || c == ':' || c == '/')) return baseKey;
        }
        return k;
    }

    public static boolean isTagged(String key) { return key != null && key.contains(SEP); }
    public static String baseOf(String key) { int i = key.indexOf(SEP); return i < 0 ? key : key.substring(0, i); }
    /** The biome id of a tagged key, or null. */
    public static String biomeOf(String key) { int i = key.indexOf(SEP); return i < 0 ? null : key.substring(i + SEP.length()); }

    // ---- colours ----

    /** Opaque ARGB for a tint kind in a biome. */
    public static int color(Kind kind, String biomeKey) {
        int slot = switch (kind) { case GRASS -> 0; case FOLIAGE -> 1; case WATER -> 2; case NONE -> -1; };
        if (slot < 0) return 0xFF808080;
        Custom c = overrides.get(biomeKey);
        if (c != null) { int v = slot == 0 ? c.grass() : slot == 1 ? c.foliage() : c.water(); if (v != -1) return v; }
        Triple t = lookup(biomeKey);
        return 0xFF000000 | (slot == 0 ? t.grass() : slot == 1 ? t.foliage() : t.water());
    }

    private static Triple lookup(String biomeKey) {
        if (biomeKey == null) return PLAINS;
        Triple t = TABLE.get(biomeKey);
        if (t != null) return t;
        // unknown (worldgen datapack / plugin biome): keyword heuristic on the path part, then plains
        String n = biomeKey.substring(biomeKey.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (has(n, "frozen_ocean", "frozen_river", "glacial_ocean")) return TABLE.get("minecraft:frozen_ocean");
        if (has(n, "warm_ocean", "tropical_ocean", "coral")) return TABLE.get("minecraft:warm_ocean");
        if (has(n, "ocean", "sea")) return TABLE.get("minecraft:ocean");
        if (has(n, "mangrove")) return TABLE.get("minecraft:mangrove_swamp");
        if (has(n, "swamp", "bog", "marsh", "fen")) return TABLE.get("minecraft:swamp");
        if (has(n, "jungle", "rainforest", "tropical", "bamboo")) return TABLE.get("minecraft:jungle");
        if (has(n, "badlands", "mesa", "canyon")) return TABLE.get("minecraft:badlands");
        if (has(n, "desert", "savanna", "dune", "steppe", "arid", "dry")) return TABLE.get("minecraft:desert");
        if (has(n, "snow", "frozen", "ice", "glacier", "glacial", "tundra", "frost", "alpine", "peaks")) return TABLE.get("minecraft:snowy_plains");
        if (has(n, "taiga", "conifer", "pine", "spruce", "fir", "boreal")) return TABLE.get("minecraft:taiga");
        if (has(n, "birch")) return TABLE.get("minecraft:birch_forest");
        if (has(n, "dark_forest", "dark_oak")) return TABLE.get("minecraft:dark_forest");
        if (has(n, "forest", "woods", "grove")) return TABLE.get("minecraft:forest");
        if (has(n, "cherry", "sakura")) return TABLE.get("minecraft:cherry_grove");
        if (has(n, "hills", "mountain", "highlands", "cliffs", "windswept", "shore")) return TABLE.get("minecraft:windswept_hills");
        return PLAINS;
    }

    private static boolean has(String s, String... words) { for (String w : words) if (s.contains(w)) return true; return false; }
}
