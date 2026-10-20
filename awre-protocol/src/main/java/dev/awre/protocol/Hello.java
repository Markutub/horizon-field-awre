package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;

/** Client -> server. Every field is untrusted. */
public record Hello(int minVersion, int maxVersion, int caps, int clientMaxFrame, String modVersion) {
    public byte[] encode() {
        return new ByteWriter().u16(minVersion).u16(maxVersion).u32(caps).u16(clientMaxFrame).string(modVersion, Awre.MAX_VERSION_CHARS).toByteArray();
    }

    public static Hello decode(byte[] body) {
        ByteReader r = new ByteReader(body);
        int min = r.u16(), max = r.u16();
        int caps = (int) r.u32();
        int maxFrame = r.u16();
        String v = r.string(Awre.MAX_VERSION_CHARS);
        r.requireEnd();
        if (min < 1 || max < min) throw new ProtocolException(Reason.BAD_FIELD, "version range " + min + ".." + max);
        if (maxFrame < 256) throw new ProtocolException(Reason.BAD_FIELD, "clientMaxFrame " + maxFrame);
        return new Hello(min, max, caps, maxFrame, v);
    }
}
