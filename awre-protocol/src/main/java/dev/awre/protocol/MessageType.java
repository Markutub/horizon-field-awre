package dev.awre.protocol;

public enum MessageType {
    HELLO(1),       // client -> server
    HELLO_ACK(2),   // server -> client
    TILE(3),        // server -> client
    TILE_ACK(4),    // client -> server
    ERROR(5),      // server -> client (diagnostic only)
    FRAGMENT(6),   // server -> client (one slice of an oversized TILE body; needs CAP_FRAGMENT_V1)
    DROP(7),        // server -> client (forget these tiles; needs CAP_DROP_V1)
    PALETTE(8);     // server -> client (block keys for material ids; needs CAP_PALETTE_V1)

    private final int id;
    MessageType(int id) { this.id = id; }
    public int id() { return id; }

    public static MessageType fromId(int id) {
        for (MessageType t : values()) if (t.id == id) return t;
        throw new ProtocolException(ProtocolException.Reason.UNKNOWN_TYPE, "type " + id);
    }
}
