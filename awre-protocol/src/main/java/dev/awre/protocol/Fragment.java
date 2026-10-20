package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;

/**
 * One slice of an oversized TILE body (the bytes {@link TileMessage#encode()} produced). Body layout (big-endian):
 * <pre>
 *  u32 msgId | u16 index | u16 count | u32 totalLen | u32 crc32(whole body) | u16 chunkSize | data[...]
 * </pre>
 * Every slice except the last carries exactly chunkSize bytes, so a slice can be placed at index*chunkSize in any
 * arrival order. All header fields are cross-checked against each other before any buffer is allocated.
 * msgId is the seq of the first FRAGMENT frame of the tile; the client acknowledges the whole tile with that id.
 */
public record Fragment(long msgId, int index, int count, int totalLen, long wholeCrc, int chunkSize, byte[] data) {

    public byte[] encode() {
        return new ByteWriter().u32(msgId).u16(index).u16(count).u32(totalLen).u32(wholeCrc).u16(chunkSize).bytes(data).toByteArray();
    }

    /** @param maxTotal the largest reassembled body this side accepts (derived from the negotiated decoded-tile limit) */
    public static Fragment decode(byte[] body, int maxTotal) {
        ByteReader r = new ByteReader(body);
        long msgId = r.u32();
        int index = r.u16(), count = r.u16();
        long total = r.u32(), crc = r.u32();
        int chunk = r.u16();
        if (count < 2 || count > Awre.MAX_FRAGMENTS) throw new ProtocolException(Reason.OUT_OF_RANGE, "fragment count " + count);
        if (index >= count) throw new ProtocolException(Reason.OUT_OF_RANGE, "fragment index " + index);
        if (chunk < 1) throw new ProtocolException(Reason.BAD_FIELD, "chunkSize 0");
        if (total < 1 || total > maxTotal) throw new ProtocolException(Reason.OUT_OF_RANGE, "totalLen " + total);
        if (total <= (long) (count - 1) * chunk || total > (long) count * chunk) throw new ProtocolException(Reason.BAD_FIELD, "totalLen inconsistent with count*chunk");
        int expect = index < count - 1 ? chunk : (int) (total - (long) (count - 1) * chunk);
        if (r.remaining() != expect) throw new ProtocolException(r.remaining() < expect ? Reason.TRUNCATED : Reason.TRAILING_BYTES, "fragment data " + r.remaining() + " vs " + expect);
        return new Fragment(msgId, index, count, (int) total, crc, chunk, r.bytes(expect));
    }
}
