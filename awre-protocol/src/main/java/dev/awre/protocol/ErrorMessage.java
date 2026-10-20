package dev.awre.protocol;

/** Server -> client diagnostic. Informational only: clients never change state because of it. */
public record ErrorMessage(int code, String detail) {
    public byte[] encode() { return new ByteWriter().u16(code).string(detail, Awre.MAX_ERROR_CHARS).toByteArray(); }

    public static ErrorMessage decode(byte[] body) {
        ByteReader r = new ByteReader(body);
        int code = r.u16();
        String d = r.string(Awre.MAX_ERROR_CHARS);
        r.requireEnd();
        return new ErrorMessage(code, d);
    }
}
