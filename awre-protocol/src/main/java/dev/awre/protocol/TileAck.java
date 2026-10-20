package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;

/** Client -> server: result of validating one TILE frame (identified by the TILE frame's seq). */
public record TileAck(long ackSeq, Status status, int tileX, int tileZ, int lod, long revision) {
    public enum Status {
        OK(0), INVALID(1), STALE(2), DUPLICATE(3);
        private final int id;
        Status(int id) { this.id = id; }
        public int id() { return id; }
        static Status fromId(int id) {
            for (Status s : values()) if (s.id == id) return s;
            throw new ProtocolException(Reason.BAD_FIELD, "ack status " + id);
        }
    }

    public byte[] encode() {
        return new ByteWriter().u32(ackSeq).u8(status.id()).i32(tileX).i32(tileZ).u8(lod).i64(revision).toByteArray();
    }

    public static TileAck decode(byte[] body) {
        ByteReader r = new ByteReader(body);
        long seq = r.u32();
        Status st = Status.fromId(r.u8());
        int x = r.i32(), z = r.i32(), lod = r.u8();
        long rev = r.i64();
        r.requireEnd();
        return new TileAck(seq, st, x, z, lod, rev);
    }
}
