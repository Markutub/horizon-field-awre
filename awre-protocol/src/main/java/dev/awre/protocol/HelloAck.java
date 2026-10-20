package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;

/** Server -> client. */
public record HelloAck(Status status, int selectedVersion, int serverMin, int serverMax, int caps,
                       int maxFrame, int maxDecodedTile, long sessionId) {
    public enum Status {
        OK(0), INCOMPATIBLE(1), DISABLED(2);
        private final int id;
        Status(int id) { this.id = id; }
        public int id() { return id; }
        static Status fromId(int id) {
            for (Status s : values()) if (s.id == id) return s;
            throw new ProtocolException(Reason.BAD_FIELD, "status " + id);
        }
    }

    public byte[] encode() {
        return new ByteWriter().u8(status.id()).u16(selectedVersion).u16(serverMin).u16(serverMax).u32(caps)
                .u16(maxFrame).u32(maxDecodedTile).i64(sessionId).toByteArray();
    }

    public static HelloAck decode(byte[] body) {
        ByteReader r = new ByteReader(body);
        Status st = Status.fromId(r.u8());
        int sel = r.u16(), smin = r.u16(), smax = r.u16();
        int caps = (int) r.u32();
        int maxFrame = r.u16();
        long maxDec = r.u32();
        long sid = r.i64();
        r.requireEnd();
        if (maxFrame > Awre.HARD_MAX_FRAME) throw new ProtocolException(Reason.OUT_OF_RANGE, "maxFrame " + maxFrame);
        if (maxDec > (1 << 20)) throw new ProtocolException(Reason.OUT_OF_RANGE, "maxDecodedTile " + maxDec);
        return new HelloAck(st, sel, smin, smax, caps, maxFrame, (int) maxDec, sid);
    }
}
