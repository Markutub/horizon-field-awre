package dev.awre.protocol;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ProtocolException.Reason;
import org.junit.jupiter.api.Test;

class FrameTest {
    private static Reason reasonOf(byte[] data, int max) {
        return assertThrows(ProtocolException.class, () -> Frame.decode(data, max)).reason();
    }

    @Test void roundTrip() {
        byte[] body = {1, 2, 3, 4, 5};
        byte[] f = Frame.encode(MessageType.TILE, 0xFFFFFFFEL, body, 1000);
        Frame d = Frame.decode(f, 1000);
        assertEquals(MessageType.TILE, d.type());
        assertEquals(0xFFFFFFFEL, d.seq());
        assertTrue(java.util.Arrays.equals(body, d.body()));
    }

    @Test void emptyBodyIsFine() {
        assertEquals(0, Frame.decode(Frame.encode(MessageType.HELLO, 1, new byte[0], 100), 100).body().length);
    }

    @Test void oversizeIsRejectedBeforeParsing() {
        // Garbage that is only too long: must report TOO_LARGE, not BAD_MAGIC => length is checked first.
        assertEquals(Reason.TOO_LARGE, reasonOf(new byte[2000], 1000));
        assertEquals(Reason.TOO_LARGE, reasonOf(new byte[Awre.HARD_MAX_FRAME + 1], Integer.MAX_VALUE));
    }

    @Test void encodeRefusesOversize() {
        assertThrows(IllegalArgumentException.class, () -> Frame.encode(MessageType.TILE, 1, new byte[2000], 1000));
        assertThrows(IllegalArgumentException.class, () -> Frame.encode(MessageType.TILE, 1, new byte[Awre.HARD_MAX_FRAME], Integer.MAX_VALUE));
    }

    @Test void rejectsTruncatedAndBadFields() {
        byte[] f = Frame.encode(MessageType.TILE, 1, new byte[20], 1000);
        assertEquals(Reason.TRUNCATED, reasonOf(new byte[5], 1000));
        assertEquals(Reason.BAD_FIELD, reasonOf(java.util.Arrays.copyOf(f, f.length - 1), 1000)); // bodyLen no longer matches
        byte[] magic = f.clone(); magic[0] ^= 1;
        assertEquals(Reason.BAD_MAGIC, reasonOf(magic, 1000));
        byte[] ver = f.clone(); ver[2] = 9;
        assertEquals(Reason.BAD_FRAME_VERSION, reasonOf(ver, 1000));
        byte[] type = f.clone(); type[3] = 99;
        assertEquals(Reason.UNKNOWN_TYPE, reasonOf(type, 1000));
    }

    @Test void crcCatchesAnySingleBitFlip() {
        byte[] f = Frame.encode(MessageType.TILE, 7, new byte[]{9, 8, 7, 6}, 1000);
        for (int i = 4; i < f.length; i++) {           // skip magic/version/type (they have their own reasons)
            byte[] g = f.clone(); g[i] ^= 0x01;
            Reason r = reasonOf(g, 1000);
            assertTrue(r == Reason.BAD_CRC || r == Reason.BAD_FIELD, "byte " + i + " -> " + r);
        }
    }
}
