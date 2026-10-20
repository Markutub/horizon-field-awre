package dev.awre.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Big-endian writer used only for messages this side constructs itself. */
public final class ByteWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

    public ByteWriter u8(int v) { out.write(v & 0xFF); return this; }
    public ByteWriter u16(int v) { out.write((v >>> 8) & 0xFF); out.write(v & 0xFF); return this; }
    public ByteWriter i32(int v) { u16(v >>> 16); u16(v); return this; }
    public ByteWriter u32(long v) { i32((int) v); return this; }
    public ByteWriter i64(long v) { i32((int) (v >>> 32)); i32((int) v); return this; }
    public ByteWriter bytes(byte[] b) { out.write(b, 0, b.length); return this; }

    /** u8 length + UTF-8 bytes; length in BYTES must fit maxBytes (and a u8). */
    public ByteWriter string(String s, int maxChars) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (s.length() > maxChars || b.length > 255) throw new IllegalArgumentException("string too long: " + s.length());
        u8(b.length);
        return bytes(b);
    }

    public byte[] toByteArray() { return out.toByteArray(); }
}
