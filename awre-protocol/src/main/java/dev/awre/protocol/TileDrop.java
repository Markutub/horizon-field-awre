package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: forget these tiles of one world/dimension. Body:
 * <pre> i64 worldMsb | i64 worldLsb | string dimension | u16 count (1..256) | count x (i32 tileX | i32 tileZ | u8 lod) </pre>
 * Dropping a tile the client does not hold is a no-op (never an error): the server shadow may be ahead of the client LRU.
 */
public record TileDrop(long worldMsb, long worldLsb, String dimension, List<Key> keys) {
    public record Key(int tileX, int tileZ, int lod) { }

    public byte[] encode() {
        if (keys.isEmpty() || keys.size() > Awre.MAX_DROP_KEYS) throw new IllegalArgumentException("drop keys " + keys.size());
        ByteWriter w = new ByteWriter().i64(worldMsb).i64(worldLsb).string(dimension, Awre.MAX_DIMENSION_CHARS).u16(keys.size());
        for (Key k : keys) w.i32(k.tileX).i32(k.tileZ).u8(k.lod);
        return w.toByteArray();
    }

    public static TileDrop decode(byte[] body, Limits lim) {
        ByteReader r = new ByteReader(body);
        long msb = r.i64(), lsb = r.i64();
        String dim = r.string(Awre.MAX_DIMENSION_CHARS);
        if (dim.isEmpty()) throw new ProtocolException(Reason.BAD_FIELD, "empty dimension");
        int n = r.u16();
        if (n < 1 || n > Awre.MAX_DROP_KEYS) throw new ProtocolException(Reason.OUT_OF_RANGE, "drop count " + n);
        List<Key> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int x = r.i32(), z = r.i32(), lod = r.u8();
            if (Math.abs((long) x) > lim.maxTileCoord() || Math.abs((long) z) > lim.maxTileCoord()) throw new ProtocolException(Reason.OUT_OF_RANGE, "tile coord");
            if (lod > lim.maxLod()) throw new ProtocolException(Reason.OUT_OF_RANGE, "lod " + lod);
            keys.add(new Key(x, z, lod));
        }
        r.requireEnd();
        return new TileDrop(msb, lsb, dim, List.copyOf(keys));
    }
}
