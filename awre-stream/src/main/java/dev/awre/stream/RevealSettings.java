package dev.awre.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * User settings of the terrain reveal effect: {@code config/awre-reveal.txt} (one {@code key = value} per line, {@code #} comments) with JVM
 * system properties on top (a property always wins over the file; see {@code docs/REVEAL_EFFECT.md}). Pure Java: the Fabric side only reads the
 * file's lines and hands them to {@link #parse}. Numeric ranges are enforced by {@link RevealAnimator.Params}, which falls back to its defaults
 * (with a warning) for an out-of-range set.
 */
public final class RevealSettings {
    /** Keys accepted in the file; "enabled" maps to the property awre.reveal (on/off), the others to awre.reveal.&lt;key&gt;. */
    public static final List<String> KEYS = List.of("enabled", "speed", "maxDelay", "duration", "peak", "depth", "emerge", "greyPeak", "rippleAmp", "rippleCycles");

    private static final AtomicReference<Map<String, String>> FILE = new AtomicReference<>(Map.of());

    private RevealSettings() { }

    public record Parsed(Map<String, String> values, List<String> rejected) { }

    public static Parsed parse(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) { rejected.add("line " + (i + 1) + ": expected 'key = value'"); continue; }
            String key = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
            if (!KEYS.contains(key)) { rejected.add("line " + (i + 1) + ": unknown key '" + key + "' (known: " + String.join(", ", KEYS) + ")"); continue; }
            if (value.isEmpty()) { rejected.add("line " + (i + 1) + ": '" + key + "' has no value"); continue; }
            if (key.equals("enabled")) {
                String v = value.toLowerCase(java.util.Locale.ROOT);
                if (v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("1")) values.put(key, "on");
                else if (v.equals("false") || v.equals("off") || v.equals("no") || v.equals("0")) values.put(key, "off");
                else rejected.add("line " + (i + 1) + ": 'enabled' must be true or false");
            } else {
                try { Double.parseDouble(value); values.put(key, value); }
                catch (NumberFormatException e) { rejected.add("line " + (i + 1) + ": '" + key + "' is not a number: " + value); }
            }
        }
        return new Parsed(values, rejected);
    }

    /** Called once by the mod after reading the file; replaces any earlier values. */
    public static void setFileValues(Map<String, String> values) { FILE.set(Map.copyOf(values)); }

    /** property name (awre.reveal / awre.reveal.&lt;key&gt;) -> value: the JVM property if set, else the file value, else null. */
    public static Function<String, String> provider(Function<String, String> properties) {
        Map<String, String> file = FILE.get();
        return prop -> {
            String p = properties.apply(prop);
            if (p != null) return p;
            if (prop.equals("awre.reveal")) return file.get("enabled");
            return prop.startsWith("awre.reveal.") ? file.get(prop.substring("awre.reveal.".length())) : null;
        };
    }

    public static boolean enabled(Function<String, String> get) {
        String v = get.apply("awre.reveal");
        return v == null || !"off".equalsIgnoreCase(v.trim());
    }

    /** A documented starting file; written into the docs and offered to users to copy. */
    public static final String EXAMPLE = """
            # AWRE terrain reveal effect (config/awre-reveal.txt). Lines starting with # are comments; delete a line to use its default.
            # JVM properties (-Dawre.reveal=off, -Dawre.reveal.speed=200 ...) override this file.
            enabled = true        # false = terrain just appears, no animation
            speed = 120           # blocks per second the ring spreads (1..4000)
            maxDelay = 6          # far terrain waits at most this many seconds (0..60)
            duration = 1.6        # seconds each part of the terrain takes to rise and settle (0.2..10)
            peak = 6              # blocks it rises above its true height (0..64)
            depth = 10            # blocks below its true height it starts from (0..64)
            emerge = 0.3          # fraction of the duration spent emerging (0..0.9)
            greyPeak = 0.85       # how grey it gets while up, 0 = never, 1 = fully (0..1)
            rippleAmp = 1.2       # blocks of settling wobble (0..16)
            rippleCycles = 2      # wobbles while settling (0..6)
            """;
}
