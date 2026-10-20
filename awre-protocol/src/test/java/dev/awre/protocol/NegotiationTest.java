package dev.awre.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NegotiationTest {
    @Test void compatibleClientBecomesActiveOnBothSides() {
        Harness h = new Harness();
        h.handshake();
        assertEquals(ServerSession.State.ACTIVE, h.server.state());
        assertEquals(ClientSession.State.ACTIVE, h.client.state());
        assertEquals(1, h.server.negotiatedVersion());
        assertEquals(1, h.client.negotiatedVersion());
        assertEquals(0xABCDEF01L, h.client.sessionId());
        assertEquals("test-1.0", h.server.clientModVersion());
    }

    @Test void clientWithOnlyNewerVersionsIsRejectedGracefully() {
        Harness h = new Harness(Limits.defaults(), 2, 3);
        h.handshake();
        assertEquals(ServerSession.State.REJECTED, h.server.state());
        assertEquals(ClientSession.State.INCOMPATIBLE, h.client.state());
        assertTrue(h.client.reason().contains("INCOMPATIBLE"));
        // no tiles may flow to a rejected peer
        assertEquals(ServerSession.SendResult.NOT_ACTIVE, h.sendTile(0, 0, 0, 1).result());
        assertEquals(0, h.client.tileCount());
    }

    @Test void serverRejectsClientWithoutTileCapability() {
        Harness h = new Harness();
        byte[] hello = Frame.encode(MessageType.HELLO, 1, new Hello(1, 1, 0, 8192, "x").encode(), 4096);
        for (byte[] r : h.server.onFrame(hello)) h.client.onFrame(r);
        assertEquals(ServerSession.State.REJECTED, h.server.state());
    }

    @Test void invalidVersionRangesAreStrikesNotCrashes() {
        Harness h = new Harness();
        // min > max, min = 0, and clientMaxFrame below the floor are all malformed HELLOs
        for (Hello bad : new Hello[]{new Hello(3, 1, 1, 8192, "x"), new Hello(0, 1, 1, 8192, "x"), new Hello(1, 1, 1, 10, "x")}) {
            assertTrue(h.server.onFrame(Frame.encode(MessageType.HELLO, 1, bad.encode(), 4096)).isEmpty());
        }
        assertEquals(3, h.server.strikes());
        assertEquals(ServerSession.State.NEW, h.server.state());
    }

    @Test void duplicateHelloIsIdempotentAndKeepsTheSession() {
        Harness h = new Harness();
        h.handshake();
        long id = h.client.sessionId();
        byte[] again = Frame.encode(MessageType.HELLO, 9, new Hello(1, 1, 1, 8192, "x").encode(), 4096);
        List<byte[]> r = h.server.onFrame(again);
        assertEquals(1, r.size());
        assertEquals(id, HelloAck.decode(Frame.decode(r.get(0), 4096).body()).sessionId());
    }

    @Test void helloFloodIsRateLimitedWithoutAmplification() {
        Harness h = new Harness();
        byte[] hello = Frame.encode(MessageType.HELLO, 1, new Hello(1, 1, 1, 8192, "x").encode(), 4096);
        int replies = 0;
        for (int i = 0; i < 50; i++) replies += h.server.onFrame(hello).size();
        assertEquals(h.lim.helloBurst(), replies);                // everything beyond the burst is silently dropped
        assertTrue(h.sc.get("server.hello.ratelimited") >= 47);
    }

    @Test void clientFrameSizeIsClampedNotTrusted() {
        Harness h = new Harness();
        byte[] hello = Frame.encode(MessageType.HELLO, 1, new Hello(1, 1, 1, 0xFFFF, "x").encode(), 4096);
        h.server.onFrame(hello);
        assertEquals(h.lim.maxFrameBytes(), h.server.negotiatedFrame());
        Harness h2 = new Harness();
        h2.server.onFrame(Frame.encode(MessageType.HELLO, 1, new Hello(1, 1, 1, 1024, "x").encode(), 4096));
        assertEquals(1024, h2.server.negotiatedFrame());           // a client may ask for LESS, never more
    }

    @Test void serverToClientTypesFromAClientAreStrikes() {
        Harness h = new Harness();
        assertTrue(h.server.onFrame(Frame.encode(MessageType.TILE, 1, new byte[4], 4096)).isEmpty());
        assertEquals(1, h.server.strikes());
    }
}
