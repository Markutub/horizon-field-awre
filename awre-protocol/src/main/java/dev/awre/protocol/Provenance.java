package dev.awre.protocol;

/** Authority of a tile. A tile is never AUTHORITATIVE merely because it was generated from a seed. */
public enum Provenance {
    PREDICTED(0), AUTHORITATIVE(1), DIRTY(2);

    private final int id;
    Provenance(int id) { this.id = id; }
    public int id() { return id; }

    public static Provenance fromId(int id) {
        for (Provenance p : values()) if (p.id == id) return p;
        throw new ProtocolException(ProtocolException.Reason.BAD_FIELD, "provenance " + id);
    }
}
