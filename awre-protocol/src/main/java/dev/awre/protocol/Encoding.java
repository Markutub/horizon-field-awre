package dev.awre.protocol;

public enum Encoding {
    RAW(0), DEFLATE(1);

    private final int id;
    Encoding(int id) { this.id = id; }
    public int id() { return id; }

    public static Encoding fromId(int id) {
        for (Encoding e : values()) if (e.id == id) return e;
        throw new ProtocolException(ProtocolException.Reason.UNSUPPORTED_ENCODING, "encoding " + id);
    }
}
