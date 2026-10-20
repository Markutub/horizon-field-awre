package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: append block keys to the palette of one world/dimension. The material ids inside terrain tiles index this palette.
 * <pre> i64 worldMsb | i64 worldLsb | string dimension | u16 startId | u16 count (1..128) | count x string(<=96 chars) </pre>
 * Entry i has id startId + i. The palette is append-only: re-sending an entry is fine if identical, a different key for a known id is an error.
 * Keys are restricted to lowercase namespaced ids ([a-z0-9_.-] and one or more ':' or '/'), so nothing here can carry markup or control characters.
 */
public record PaletteUpdate(long worldMsb, long worldLsb, String dimension, int startId, List<String> keys) {

    public byte[] encode() {
        if (keys.isEmpty() || keys.size() > Awre.MAX_PALETTE_PER_MESSAGE || startId < 0 || startId + keys.size() > Awre.MAX_PALETTE_ENTRIES) throw new IllegalArgumentException("palette update bounds");
        ByteWriter w = new ByteWriter().i64(worldMsb).i64(worldLsb).string(dimension, Awre.MAX_DIMENSION_CHARS).u16(startId).u16(keys.size());
        for (String k : keys) { if (!validKey(k)) throw new IllegalArgumentException("palette key " + k); w.string(k, Awre.MAX_PALETTE_KEY_CHARS); }
        return w.toByteArray();
    }

    public static PaletteUpdate decode(byte[] body) {
        ByteReader r = new ByteReader(body);
        long msb = r.i64(), lsb = r.i64();
        String dim = r.string(Awre.MAX_DIMENSION_CHARS);
        if (dim.isEmpty()) throw new ProtocolException(Reason.BAD_FIELD, "empty dimension");
        int start = r.u16(), n = r.u16();
        if (n < 1 || n > Awre.MAX_PALETTE_PER_MESSAGE) throw new ProtocolException(Reason.OUT_OF_RANGE, "palette count " + n);
        if (start + n > Awre.MAX_PALETTE_ENTRIES) throw new ProtocolException(Reason.OUT_OF_RANGE, "palette id " + (start + n));
        List<String> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String k = r.string(Awre.MAX_PALETTE_KEY_CHARS);
            if (!validKey(k)) throw new ProtocolException(Reason.BAD_FIELD, "palette key characters");
            keys.add(k);
        }
        r.requireEnd();
        return new PaletteUpdate(msb, lsb, dim, start, List.copyOf(keys));
    }

    public static boolean validKey(String k) {
        if (k == null || k.isEmpty() || k.length() > Awre.MAX_PALETTE_KEY_CHARS) return false;
        boolean sep = false;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c == ':' || c == '/') sep = true;
            else if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')) return false;
        }
        return sep;
    }
}
