package dev.awre.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The AWRE client's main options as a file (config/awre-client.txt), so they no longer exist only as JVM flags. Precedence for every option:
 * the {@code -D} JVM property (kept for CI and launchers) beats the file, which beats the built-in default. Each line is {@code key = value}; {@code #}
 * starts a comment. Unknown keys and invalid values are REJECTED with a message (never applied, never silently clamped), the rest of the file still
 * loads. Values are validated here, not where they are used, so a typo cannot reach the renderer. Pure Java; the Fabric class only reads the file.
 */
public final class ClientOptions {
    /** One option: the file key, the JVM property it mirrors, its default, a validator (null = ok, else the "expected ..." text) and a help line. */
    public record Option(String key, String property, String def, Function<String, String> check, String help) { }

    public static final List<Option> OPTIONS = List.of(
        new Option("render", "awre.render", "auto", v -> oneOf(v, "auto", "on", "off"),
                "auto = draw AWRE terrain unless Distant Horizons or Voxy is loaded; on = always; off = never (the client still connects and receives tiles)"),
        new Option("maxDistance", "awre.view.maxDistance", "16384", v -> number(v, 64, 16384),
                "farthest distance in BLOCKS AWRE will draw (64..16384). What you can see is also limited by the server's stream.radius and by the projection far plane"),
        new Option("far", "awre.far", "auto", v -> farPlane(v),
                "far plane of the AWRE pass: auto, off (vanilla's projection) or a distance in blocks (64..65536)"),
        new Option("vanillaRadius", "awre.vanilla.radius", "effective", v -> oneOf(v, "effective", "client"),
                "which render distance counts as vanilla's: effective = min(your option, server view distance); client = your raw option (for Bobby-like mods)"),
        new Option("iris", "awre.iris", "auto", v -> oneOf(v, "auto", "assign"),
                "auto = only detect Iris; assign = EXPERIMENTAL: ask Iris to shade AWRE terrain with the shader pack's terrain program"));

    private static final AtomicReference<Map<String, String>> FILE = new AtomicReference<>(Map.of());   // keyed by PROPERTY name

    private ClientOptions() { }

    public record Parsed(Map<String, String> values, List<String> rejected) { }

    private static String oneOf(String v, String... allowed) {
        for (String a : allowed) if (a.equals(v.toLowerCase(Locale.ROOT))) return null;
        return "one of " + String.join(", ", allowed);
    }
    private static String number(String v, double lo, double hi) {
        try { double d = Double.parseDouble(v); if (Double.isFinite(d) && d >= lo && d <= hi) return null; } catch (NumberFormatException ignored) { }
        return "a number from " + (long) lo + " to " + (long) hi;
    }
    private static String farPlane(String v) {
        String l = v.toLowerCase(Locale.ROOT);
        return l.equals("auto") || l.equals("off") || number(v, 64, 65536) == null ? null : "auto, off, or a number from 64 to 65536";
    }

    /** Parses file lines. Values are normalised to lower case for enumerations (render, vanillaRadius, iris, far=auto/off). */
    public static Parsed parse(List<String> lines) {
        Map<String, Option> byKey = new LinkedHashMap<>();
        for (Option o : OPTIONS) byKey.put(o.key().toLowerCase(Locale.ROOT), o);
        Map<String, String> values = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int hash = line.indexOf('#'); if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) { rejected.add("line " + (i + 1) + ": expected 'key = value', got '" + line + "'"); continue; }
            String key = line.substring(0, eq).trim(), val = line.substring(eq + 1).trim();
            Option o = byKey.get(key.toLowerCase(Locale.ROOT));
            if (o == null) { rejected.add("line " + (i + 1) + ": unknown option '" + key + "' (known: " + knownKeys() + ")"); continue; }
            if (val.isEmpty()) { rejected.add("line " + (i + 1) + ": '" + o.key() + "' has no value"); continue; }
            String why = o.check().apply(val);
            if (why != null) { rejected.add("line " + (i + 1) + ": '" + o.key() + " = " + val + "' rejected, expected " + why + "; using the default (" + o.def() + ")"); continue; }
            if (values.containsKey(o.property())) rejected.add("line " + (i + 1) + ": '" + o.key() + "' given twice; the last value wins");
            values.put(o.property(), isNumeric(val) ? val : val.toLowerCase(Locale.ROOT));
        }
        return new Parsed(Map.copyOf(values), List.copyOf(rejected));
    }

    private static boolean isNumeric(String v) { try { Double.parseDouble(v); return true; } catch (NumberFormatException e) { return false; } }
    private static String knownKeys() { List<String> k = new ArrayList<>(); for (Option o : OPTIONS) k.add(o.key()); return String.join(", ", k); }

    public static void setFileValues(Map<String, String> byProperty) { FILE.set(Map.copyOf(byProperty)); }

    /** JVM property first (non-blank), then the file, then {@code def}. */
    public static String get(String property, String def) { return resolve(System::getProperty, FILE.get(), property, def); }

    public static String resolve(Function<String, String> props, Map<String, String> file, String property, String def) {
        String p = props.apply(property);
        if (p != null && !p.isBlank()) return p.trim();
        String f = file.get(property);
        return f != null ? f : def;
    }

    /** One line for the log: every option with its value and where it came from. */
    public static String describe(Function<String, String> props, Map<String, String> file) {
        StringBuilder sb = new StringBuilder();
        for (Option o : OPTIONS) {
            String p = props.apply(o.property());
            String src = p != null && !p.isBlank() ? "jvm" : file.containsKey(o.property()) ? "file" : "default";
            if (sb.length() > 0) sb.append(' ');
            sb.append(o.key()).append('=').append(resolve(props, file, o.property(), o.def())).append('(').append(src).append(')');
        }
        return sb.toString();
    }
    public static String describe() { return describe(System::getProperty, FILE.get()); }

    /** The commented template written to config/awre-client.txt when the file does not exist: every option, its default and what it does. */
    public static String example() {
        StringBuilder sb = new StringBuilder("# AWRE client options. Remove the leading # from a line to change it. A -D JVM flag (e.g. -Dawre.render=off) overrides this file.\n");
        sb.append("# Invalid values are rejected with a warning in the game log and the default is used. Restart the game after editing.\n");
        sb.append("# The amount of terrain you see is limited by THREE things: the server's stream.radius / max-held-tiles, maxDistance below, and the projection far plane.\n\n");
        for (Option o : OPTIONS) sb.append("# ").append(o.help()).append('\n').append("# ").append(o.key()).append(" = ").append(o.def()).append("\n\n");
        return sb.toString();
    }
}
