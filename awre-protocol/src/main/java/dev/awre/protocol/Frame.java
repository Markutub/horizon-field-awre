package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.zip.CRC32;

/**
 * Wire frame (big-endian):
 * <pre>
 *  u16 magic | u8 frameVersion | u8 type | u32 seq | u16 bodyLen | body[bodyLen] | u32 crc32(all preceding bytes)
 * </pre>
 * The total length is checked against the caller's limit BEFORE any field is parsed or any body is copied.
 */
public record Frame(MessageType type, long seq, byte[] body) {
    public static final int HEADER = 10;
    public static final int TRAILER = 4;
    public static final int OVERHEAD = HEADER + TRAILER;

    public static byte[] encode(MessageType type, long seq, byte[] body, int maxFrame) {
        int total = OVERHEAD + body.length;
        if (total > maxFrame || total > Awre.HARD_MAX_FRAME || body.length > 0xFFFF) {
            throw new IllegalArgumentException("frame too large: " + total + " > " + Math.min(maxFrame, Awre.HARD_MAX_FRAME));
        }
        ByteWriter w = new ByteWriter();
        w.u16(Awre.MAGIC).u8(Awre.FRAME_VERSION).u8(type.id()).u32(seq).u16(body.length).bytes(body);
        byte[] head = w.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(head, 0, head.length);
        return new ByteWriter().bytes(head).u32(crc.getValue()).toByteArray();
    }

    /** @throws ProtocolException on anything that is not exactly one well-formed frame within maxFrame bytes */
    public static Frame decode(byte[] data, int maxFrame) {
        int cap = Math.min(maxFrame, Awre.HARD_MAX_FRAME);
        if (data.length > cap) throw new ProtocolException(Reason.TOO_LARGE, data.length + " > " + cap);
        if (data.length < OVERHEAD) throw new ProtocolException(Reason.TRUNCATED, "frame " + data.length);
        ByteReader r = new ByteReader(data);
        if (r.u16() != Awre.MAGIC) throw new ProtocolException(Reason.BAD_MAGIC, "magic");
        if (r.u8() != Awre.FRAME_VERSION) throw new ProtocolException(Reason.BAD_FRAME_VERSION, "frame version");
        MessageType type = MessageType.fromId(r.u8());
        long seq = r.u32();
        int bodyLen = r.u16();
        if (bodyLen != data.length - OVERHEAD) throw new ProtocolException(Reason.BAD_FIELD, "bodyLen " + bodyLen + " vs " + (data.length - OVERHEAD));
        byte[] body = r.bytes(bodyLen);
        long crcStored = r.u32();
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - TRAILER);
        if (crc.getValue() != crcStored) throw new ProtocolException(Reason.BAD_CRC, "crc");
        return new Frame(type, seq, body);
    }
}
