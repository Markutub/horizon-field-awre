package dev.awre.protocol;

/** Protocol constants. Everything here is part of the wire contract; see docs/AWRE_PROTOCOL.md. */
public final class Awre {
    private Awre() { }

    /** Single plugin channel; messages are multiplexed by {@link MessageType}. Must be lowercase "namespace:path". */
    public static final String CHANNEL = "awre:main";
    public static final String NAMESPACE = "awre";
    public static final String PATH = "main";

    /** Protocol versions this build can speak (inclusive). */
    public static final int PROTOCOL_MIN = 1;
    public static final int PROTOCOL_MAX = 1;

    /** Frame magic and framing-format version. The framing format never changes within a major protocol line, so HELLO stays readable. */
    public static final int MAGIC = 0xA3E1;
    public static final int FRAME_VERSION = 1;

    /** Capability bits. */
    public static final int CAP_TILE_V1 = 1;
    /** Tiles larger than one frame are split into FRAGMENT frames (docs/AWRE_G1_FEATURES.md section 1). */
    public static final int CAP_FRAGMENT_V1 = 2;
    /** Server may tell the client to forget tiles (DROP), so the server-side shadow of the client store stays exact. */
    public static final int CAP_DROP_V1 = 4;
    /** Server sends the block-key palette that the material ids inside terrain tiles refer to (PALETTE). */
    public static final int CAP_PALETTE_V1 = 8;
    public static final int CAPS_ALL = CAP_TILE_V1 | CAP_FRAGMENT_V1 | CAP_DROP_V1 | CAP_PALETTE_V1;
    public static final int MAX_DROP_KEYS = 256;
    public static final int MAX_PALETTE_ENTRIES = 32768;     // ids fit a u16 and a Java short >= 0
    public static final int MAX_PALETTE_PER_MESSAGE = 128;
    public static final int MAX_PALETTE_KEY_CHARS = 96;
    public static final int MAX_CLIENT_PALETTES = 16;        // distinct world/dimension palettes a client keeps

    /** Fragment bounds. Nothing here grows with remote input. */
    public static final int MAX_FRAGMENTS = 64;          // per tile; 64 x ~16 KB = 1 MB, above any allowed decoded tile
    public static final int MAX_ASSEMBLIES = 2;          // concurrent partial tiles per connection
    public static final int ASSEMBLY_TIMEOUT_TICKS = 200; // 10 s at 20 TPS
    public static final int FRAGMENT_HEADER = 18;

    /**
     * Absolute frame ceiling. Chosen below Bukkit's Messenger.MAX_MESSAGE_SIZE (32766) and the vanilla client-to-server
     * custom-payload limit (32767), so one frame always fits one plugin message in both directions. Larger tiles need
     * fragmentation (NOT implemented in G0; see docs).
     */
    public static final int HARD_MAX_FRAME = 32000;

    public static final int MAX_DIMENSION_CHARS = 48;
    public static final int MAX_VERSION_CHARS = 32;
    public static final int MAX_ERROR_CHARS = 64;
}
