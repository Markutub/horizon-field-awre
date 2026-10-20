package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;
import java.nio.charset.StandardCharsets;

/** Bounds-checked big-endian reader. Every length is validated against the remaining bytes BEFORE any allocation. */
public final class ByteReader {
    private final byte[] data;
    private int pos;
    private final int end;

    public ByteReader(byte[] data) { this(data, 0, data.length); }

    public ByteReader(byte[] data, int off, int len) {
        if (off < 0 || len < 0 || off + len > data.length) throw new IllegalArgumentException("bad slice");
        this.data = data; this.pos = off; this.end = off + len;
    }

    public int remaining() { return end - pos; }

    private void need(int n) {
        if (n < 0 || n > end - pos) throw new ProtocolException(Reason.TRUNCATED, "need " + n + " have " + (end - pos));
    }

    public int u8() { need(1); return data[pos++] & 0xFF; }
    public int u16() { need(2); int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF); pos += 2; return v; }
    public int i32() { need(4); int v = (u16() << 16) | u16(); return v; }
    public long u32() { return i32() & 0xFFFFFFFFL; }
    public long i64() { long hi = i32(); long lo = u32(); return (hi << 32) | lo; }

    public byte[] bytes(long n) {
        if (n < 0 || n > remaining()) throw new ProtocolException(Reason.TRUNCATED, "bytes " + n + " have " + remaining());
        byte[] b = new byte[(int) n];
        System.arraycopy(data, pos, b, 0, b.length);
        pos += b.length;
        return b;
    }

    public String string(int maxChars) {
        int len = u8();
        need(len);
        String s = new String(data, pos, len, StandardCharsets.UTF_8);
        pos += len;
        if (s.length() > maxChars) throw new ProtocolException(Reason.BAD_FIELD, "string too long");
        return s;
    }

    public void requireEnd() {
        if (remaining() != 0) throw new ProtocolException(Reason.TRAILING_BYTES, remaining() + " bytes");
    }
}
