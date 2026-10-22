package dev.horizonfield.core.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User/pack-supplied colours for block ids, applied before the built-in table ({@link BlockColors#setOverrides}). Pure Java.
 * Exists for blocks the built-in table cannot know: modded or datapack blocks, and plugin "custom blocks" (e.g. ItemsAdder carrier
 * blocks). Limitation: it matches the palette's block id only, never block-state properties, so two custom blocks sharing one
 * carrier id (several note_block states) cannot get different colours.
 *
 * <p>Text format, one rule per line: {@code namespace:block = #RRGGBB}; a trailing {@code *} matches a prefix
 * ({@code mymod:* = #808080}); exact keys win over prefixes, longer prefixes over shorter; {@code #} at line start = comment.
 */
public final class ColorOverrides {
    public static final ColorOverrides EMPTY = new ColorOverrides(Map.of(), Map.of(), List.of());

    private final Map<String, Integer> exact, prefix;
    private final List<String> rejected;
    private final List<String> prefixesLongestFirst;

    private ColorOverrides(Map<String, Integer> exact, Map<String, Integer> prefix, List<String> rejected) {
        this.exact = exact; this.prefix = prefix; this.rejected = rejected;
        List<String> p = new ArrayList<>(prefix.keySet());
        p.sort((a, b) -> Integer.compare(b.length(), a.length()));
        this.prefixesLongestFirst = p;
    }

    /** Lines that could not be parsed, with a reason, so the caller can log them. */
    public List<String> rejected() { return rejected; }
    public int size() { return exact.size() + prefix.size(); }

    /** @return opaque ARGB, or null if no rule matches */
    public Integer lookup(String key) {
        if (key == null) return null;
        Integer c = exact.get(key);
        if (c != null) return c;
        for (String p : prefixesLongestFirst) if (key.startsWith(p)) return prefix.get(p);
        return null;
    }

    public static ColorOverrides parse(List<String> lines) {
        Map<String, Integer> ex = new HashMap<>(), pre = new HashMap<>();
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (String raw : lines) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) { bad.add("line " + n + ": missing '='"); continue; }
            String key = line.substring(0, eq).strip(), val = line.substring(eq + 1).strip();
            if (key.isEmpty() || key.indexOf(' ') >= 0) { bad.add("line " + n + ": bad block id"); continue; }
            if (!val.startsWith("#") || val.length() != 7) { bad.add("line " + n + ": colour must be #RRGGBB"); continue; }
            int rgb;
            try { rgb = Integer.parseInt(val.substring(1), 16); } catch (NumberFormatException e) { bad.add("line " + n + ": colour is not hex"); continue; }
            int argb = 0xFF000000 | rgb;
            if (key.endsWith("*")) pre.put(key.substring(0, key.length() - 1), argb); else ex.put(key, argb);
        }
        return new ColorOverrides(ex, pre, List.copyOf(bad));
    }
}
