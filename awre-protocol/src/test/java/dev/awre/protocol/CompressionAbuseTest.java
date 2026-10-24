package dev.awre.protocol;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ProtocolException.Reason;
import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** Decompression bombs: a few KB on the wire that would inflate to hundreds of MB. The decoder must stop at its limit, quickly and without allocating the full size. */
class CompressionAbuseTest {
    /** Raw deflate (nowrap, like the protocol) of {@code size} zero bytes. */
    private static byte[] bomb(long size) {
        Deflater d = new Deflater(9, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] zeros = new byte[1 << 16], buf = new byte[1 << 14];
        for (long done = 0; done < size; ) {
            int n = (int) Math.min(zeros.length, size - done);
            d.setInput(zeros, 0, n);
            while (!d.needsInput()) { int k = d.deflate(buf); out.write(buf, 0, k); }
            done += n;
        }
        d.finish();
        while (!d.finished()) { int k = d.deflate(buf); out.write(buf, 0, k); }
        d.end();
        return out.toByteArray();
    }

    @Test void aBombIsRejectedAtTheLimitQuicklyWithoutAllocatingItsFullSize() {
        for (long size : new long[]{70_000L, 1L << 20, 64L << 20, 256L << 20}) {
            byte[] comp = bomb(size);
            assertTrue(comp.length < 300_000, "the bomb really is small on the wire: " + comp.length + " B for " + size);
            long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long t0 = System.nanoTime();
            ProtocolException e = assertThrows(ProtocolException.class, () -> Codec.inflateBounded(comp, 65_536));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(Reason.INFLATE_LIMIT, e.reason(), "size " + size);
            assertTrue(ms < 500, "rejected in " + ms + " ms");
            long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            assertTrue(after - before < 8L << 20, "no large allocation was made for a " + size + "-byte bomb: " + (after - before) + " B");
        }
    }

    @Test void aStreamExactlyAtTheLimitPassesAndOneByteOverFails() {
        assertEquals(65_536, Codec.inflateBounded(bomb(65_536), 65_536).length);
        assertEquals(Reason.INFLATE_LIMIT, assertThrows(ProtocolException.class, () -> Codec.inflateBounded(bomb(65_537), 65_536)).reason());
    }

    @Test void garbageThatIsNotDeflateNeverEscapesAsAnUncheckedFailure() {
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < 2000; i++) {
            byte[] junk = new byte[1 + r.nextInt(300)];
            r.nextBytes(junk);
            try { Codec.inflateBounded(junk, 65_536); }
            catch (ProtocolException expected) { /* the only allowed failure */ }
        }
    }
}
