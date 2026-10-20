package dev.awre.protocol;

import java.util.Arrays;

/**
 * Builds deliberately broken frames for the on-server bounded-decode test (G0). Used by /awre sendbad and by unit tests.
 * Kinds: crc, truncated, bomb, oversize (above the session limit), huge (above HARD_MAX_FRAME, exercises the codec-level skip).
 */
public final class FaultInjector {
    private FaultInjector() { }

    public static final java.util.List<String> KINDS = java.util.List.of("crc", "truncated", "bomb", "oversize", "huge");

    public static byte[] build(String kind, long seq) {
        switch (kind) {
            case "crc": {
                byte[] f = Frame.encode(MessageType.ERROR, seq, new ErrorMessage(1, "fault:crc").encode(), Awre.HARD_MAX_FRAME);
                f[f.length - 1] ^= 0x5A;
                return f;
            }
            case "truncated": {
                byte[] f = Frame.encode(MessageType.ERROR, seq, new ErrorMessage(1, "fault:truncated").encode(), Awre.HARD_MAX_FRAME);
                return Arrays.copyOf(f, f.length - 7);
            }
            case "bomb": { // ~10 MB of zeros deflated (~10 KB) while claiming a 4 KB decoded size: must hit INFLATE_LIMIT
                byte[] zeros = new byte[10 * 1024 * 1024];
                byte[] comp = Codec.deflate(zeros);
                TileMessage t = new TileMessage(1, 2, "minecraft:overworld", 0, 0, 0, 1, Provenance.AUTHORITATIVE, Encoding.DEFLATE, 4096, 0, comp);
                return Frame.encode(MessageType.TILE, seq, t.encode(), Awre.HARD_MAX_FRAME);
            }
            case "oversize": { // valid frame, larger than the default 16 KiB session limit
                byte[] body = new byte[20000 - Frame.OVERHEAD];
                return Frame.encode(MessageType.ERROR, seq, body, Awre.HARD_MAX_FRAME);
            }
            case "huge": { // not a valid frame at all: bigger than HARD_MAX_FRAME, still below Bukkit's plugin message cap
                return new byte[32500];
            }
            default: throw new IllegalArgumentException("unknown fault kind " + kind);
        }
    }
}
