package dev.awre.protocol;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ProtocolException.Reason;
import org.junit.jupiter.api.Test;

class TileMessageTest {
    private static final Limits L = Limits.defaults();

    private static TileMessage sample(Encoding enc) {
        return TileMessage.create(11, 22, "minecraft:overworld", 5, -7, 2, 9, Provenance.AUTHORITATIVE, enc, TestTile.generate(5, -7, 2, 9));
    }

    @Test void roundTripDeflateAndRaw() {
        for (Encoding e : Encoding.values()) {
            TileMessage t = sample(e);
            TileMessage d = TileMessage.decode(t.encode(), L);
            assertEquals("minecraft:overworld", d.dimension());
            assertEquals(5, d.tileX()); assertEquals(-7, d.tileZ()); assertEquals(2, d.lod()); assertEquals(9L, d.revision());
            assertEquals(Provenance.AUTHORITATIVE, d.provenance());
            assertTrue(TestTile.verify(5, -7, 2, 9, d.decodePayload()));
        }
    }

    @Test void deflateActuallyShrinksTheTestTile() {
        assertTrue(sample(Encoding.DEFLATE).payload().length < TestTile.BYTES);
    }

    private static Reason decodeReason(byte[] body) {
        return assertThrows(ProtocolException.class, () -> TileMessage.decode(body, L)).reason();
    }

    @Test void rejectsOutOfRangeFields() {
        TileMessage b = sample(Encoding.RAW);
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(new TileMessage(1, 2, "d", 0, 0, 99, 1, Provenance.AUTHORITATIVE, Encoding.RAW, 512, 0, new byte[0]).encode())); // lod
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(new TileMessage(1, 2, "d", Integer.MAX_VALUE, 0, 0, 1, Provenance.AUTHORITATIVE, Encoding.RAW, 512, 0, new byte[0]).encode()));
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(new TileMessage(1, 2, "d", 0, Integer.MIN_VALUE, 0, 1, Provenance.AUTHORITATIVE, Encoding.RAW, 512, 0, new byte[0]).encode()));
        assertEquals(Reason.BAD_FIELD, decodeReason(new TileMessage(1, 2, "d", 0, 0, 0, -1, Provenance.AUTHORITATIVE, Encoding.RAW, 512, 0, new byte[0]).encode()));
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(new TileMessage(1, 2, "d", 0, 0, 0, 1, Provenance.AUTHORITATIVE, Encoding.RAW, L.maxDecodedTileBytes() + 1, 0, new byte[0]).encode()));
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(new TileMessage(1, 2, "d", 0, 0, 0, 1, Provenance.AUTHORITATIVE, Encoding.RAW, 0, 0, new byte[0]).encode()));
        assertEquals(Reason.BAD_FIELD, decodeReason(new TileMessage(1, 2, "", 0, 0, 0, 1, Provenance.AUTHORITATIVE, Encoding.RAW, 512, 0, new byte[0]).encode()));
        assertNotNull(b);
    }

    @Test void rejectsLengthLies() {
        byte[] ok = sample(Encoding.RAW).encode();
        assertEquals(Reason.TRAILING_BYTES, decodeReason(java.util.Arrays.copyOf(ok, ok.length + 3)));
        assertEquals(Reason.TRUNCATED, decodeReason(java.util.Arrays.copyOf(ok, ok.length - 3)));
        // declare a gigantic compressedLen with a tiny body: must fail without allocating that much
        byte[] lie = ok.clone();
        int lenPos = lie.length - 512 - 4;       // RAW payload is 512 bytes, preceded by the u32 compressedLen
        lie[lenPos] = (byte) 0x7F; lie[lenPos + 1] = (byte) 0xFF; lie[lenPos + 2] = (byte) 0xFF; lie[lenPos + 3] = (byte) 0xFF;
        assertEquals(Reason.TRUNCATED, decodeReason(lie));
    }

    @Test void rawPayloadLargerThanDeclaredMaxIsRejected() {
        TileMessage t = new TileMessage(1, 2, "d", 0, 0, 0, 1, Provenance.AUTHORITATIVE, Encoding.RAW, 16, 0, new byte[100]);
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(t.encode()));
    }

    @Test void checksumMismatchIsDetected() {
        TileMessage good = sample(Encoding.DEFLATE);
        TileMessage bad = new TileMessage(good.worldMsb(), good.worldLsb(), good.dimension(), good.tileX(), good.tileZ(), good.lod(), good.revision(),
                good.provenance(), good.encoding(), good.maxDecodedLen(), good.payloadCrc32() ^ 1, good.payload());
        TileMessage d = TileMessage.decode(bad.encode(), L);
        assertEquals(Reason.CHECKSUM_MISMATCH, assertThrows(ProtocolException.class, d::decodePayload).reason());
    }

    @Test void inflateBombIsStoppedAtTheDeclaredLimit() {
        byte[] comp = Codec.deflate(new byte[10 * 1024 * 1024]);        // ~10 KB on the wire, 10 MB if expanded
        assertTrue(comp.length < 20000, "bomb should be small on the wire: " + comp.length);
        TileMessage t = new TileMessage(1, 2, "d", 0, 0, 0, 1, Provenance.AUTHORITATIVE, Encoding.DEFLATE, 4096, 0, comp);
        TileMessage d = TileMessage.decode(t.encode(), L);
        assertEquals(Reason.INFLATE_LIMIT, assertThrows(ProtocolException.class, d::decodePayload).reason());
    }

    @Test void inflateRejectsTruncatedAndTrailingStreams() {
        byte[] comp = Codec.deflate(TestTile.generate(1, 1, 0, 1));
        assertEquals(Reason.TRUNCATED, assertThrows(ProtocolException.class, () -> Codec.inflateBounded(java.util.Arrays.copyOf(comp, comp.length - 3), 4096)).reason());
        byte[] extra = java.util.Arrays.copyOf(comp, comp.length + 4);
        assertEquals(Reason.TRAILING_BYTES, assertThrows(ProtocolException.class, () -> Codec.inflateBounded(extra, 4096)).reason());
        assertEquals(Reason.BAD_FIELD, assertThrows(ProtocolException.class, () -> Codec.inflateBounded(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1, 2, 3}, 4096)).reason());
    }

    @Test void unknownEnumsAreRejected() {
        byte[] ok = sample(Encoding.RAW).encode();
        // provenance and encoding bytes sit right after revision: locate by re-encoding with known values
        TileMessage t = sample(Encoding.RAW);
        byte[] b = t.encode();
        int provPos = 8 + 8 + 1 + t.dimension().length() + 4 + 4 + 1 + 8;
        byte[] badProv = b.clone(); badProv[provPos] = 9;
        assertEquals(Reason.BAD_FIELD, decodeReason(badProv));
        byte[] badEnc = b.clone(); badEnc[provPos + 1] = 9;
        assertEquals(Reason.UNSUPPORTED_ENCODING, decodeReason(badEnc));
        assertNotNull(ok);
    }
}
