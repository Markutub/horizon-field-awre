package dev.awre.stream;

import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer for result reports (one line per report, see {@link JsonlLog}). Supports null, Boolean, Number,
 * CharSequence, Map (keys via toString, iteration order kept: pass a LinkedHashMap for a stable layout), Iterable and Object[]; anything else is
 * written as its toString(). NaN and infinities become null (JSON has no such numbers). Output never contains a raw line break, and lone
 * surrogates are escaped, so every line is valid UTF-8 JSON. Nesting deeper than {@value #MAX_DEPTH} is rejected (guards against cycles).
 */
public final class ReportJson {
    public static final int MAX_DEPTH = 12;
    private ReportJson() { }

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("report nests deeper than " + MAX_DEPTH + " (cycle?)");
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null"); else sb.append(v);
            return;
        }
        if (v instanceof Number) { sb.append(v); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(','); first = false;
                string(sb, String.valueOf(e.getKey())); sb.append(':'); write(sb, e.getValue(), depth + 1);
            }
            sb.append('}'); return;
        }
        if (v instanceof Iterable<?> it) {
            sb.append('['); boolean first = true;
            for (Object o : it) { if (!first) sb.append(','); first = false; write(sb, o, depth + 1); }
            sb.append(']'); return;
        }
        if (v instanceof Object[] arr) { write(sb, List.of(arr), depth); return; }
        string(sb, v.toString());
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') sb.append(String.format("\\u%04x", (int) c));
                    else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) { sb.append(c).append(s.charAt(++i)); }
                    else if (Character.isSurrogate(c)) sb.append(String.format("\\u%04x", (int) c));       // lone surrogate: not valid UTF-8
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
