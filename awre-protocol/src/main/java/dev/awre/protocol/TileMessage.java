package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.zip.CRC32;

/**
 * Server -> client tile. Body layout:
 * <pre>
 *  i64 worldMsb | i64 worldLsb | string dimension(<=48) | i32 tileX | i32 tileZ | u8 lod | i64 revision
 *  | u8 provenance | u8 encoding | u32 maxDecodedLen | u32 payloadCrc32(of DECODED bytes) | u32 compressedLen | payload[compressedLen]
 * </pre>
 * Validation order guarantees that no allocation is made from a length before that length was checked.
 */
public record TileMessage(long worldMsb, long worldLsb, String dimension, int tileX, int tileZ, int lod, long revision,
                          Provenance provenance, Encoding encoding, int maxDecodedLen, long payloadCrc32, byte[] payload) {

    public static TileMessage create(long worldMsb, long worldLsb, String dimension, int tileX, int tileZ, int lod, long revision,
                                     Provenance provenance, Encoding encoding, byte[] decoded) {
        CRC32 crc = new CRC32();
        crc.update(decoded, 0, decoded.length);
        byte[] payload = encoding == Encoding.DEFLATE ? Codec.deflate(decoded) : decoded;
        return new TileMessage(worldMsb, worldLsb, dimension, tileX, tileZ, lod, revision, provenance, encoding,
                decoded.length, crc.getValue(), payload);
    }

    public byte[] encode() {
        return new ByteWriter().i64(worldMsb).i64(worldLsb).string(dimension, Awre.MAX_DIMENSION_CHARS)
                .i32(tileX).i32(tileZ).u8(lod).i64(revision).u8(provenance.id()).u8(encoding.id())
                .u32(maxDecodedLen).u32(payloadCrc32).u32(payload.length).bytes(payload).toByteArray();
    }

    public static TileMessage decode(byte[] body, Limits lim) {
        ByteReader r = new ByteReader(body);
        long msb = r.i64(), lsb = r.i64();
        String dim = r.string(Awre.MAX_DIMENSION_CHARS);
        if (dim.isEmpty()) throw new ProtocolException(Reason.BAD_FIELD, "empty dimension");
        int tx = r.i32(), tz = r.i32();
        if (Math.abs((long) tx) > lim.maxTileCoord() || Math.abs((long) tz) > lim.maxTileCoord()) throw new ProtocolException(Reason.OUT_OF_RANGE, "tile coord");
        int lod = r.u8();
        if (lod > lim.maxLod()) throw new ProtocolException(Reason.OUT_OF_RANGE, "lod " + lod);
        long rev = r.i64();
        if (rev < 0) throw new ProtocolException(Reason.BAD_FIELD, "revision");
        Provenance prov = Provenance.fromId(r.u8());
        Encoding enc = Encoding.fromId(r.u8());
        long maxDec = r.u32();
        if (maxDec < 1 || maxDec > lim.maxDecodedTileBytes()) throw new ProtocolException(Reason.OUT_OF_RANGE, "maxDecodedLen " + maxDec);
        long crc = r.u32();
        long clen = r.u32();
        if (clen != r.remaining()) throw new ProtocolException(clen > r.remaining() ? Reason.TRUNCATED : Reason.TRAILING_BYTES, "compressedLen " + clen + " vs " + r.remaining());
        if (enc == Encoding.RAW && clen > maxDec) throw new ProtocolException(Reason.OUT_OF_RANGE, "raw payload > maxDecodedLen");
        byte[] payload = r.bytes(clen);
        return new TileMessage(msb, lsb, dim, tx, tz, lod, rev, prov, enc, (int) maxDec, crc, payload);
    }

    /** Bounded decode + integrity check. */
    public byte[] decodePayload() {
        byte[] out = encoding == Encoding.DEFLATE ? Codec.inflateBounded(payload, maxDecodedLen) : payload;
        if (out.length > maxDecodedLen) throw new ProtocolException(Reason.INFLATE_LIMIT, "decoded " + out.length);
        CRC32 crc = new CRC32();
        crc.update(out, 0, out.length);
        if (crc.getValue() != payloadCrc32) throw new ProtocolException(Reason.CHECKSUM_MISMATCH, "payload crc");
        return out;
    }
}
