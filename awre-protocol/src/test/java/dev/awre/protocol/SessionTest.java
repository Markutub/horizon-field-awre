package dev.awre.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SessionTest {
    @Test void endToEndTileIsVerifiedAndAcked() {
        Harness h = new Harness();
        h.handshake();
        ServerSession.TileSend s = h.sendTile(3, -4, 1, 1);
        assertEquals(ServerSession.SendResult.SENT, s.result());
        assertEquals(1, h.client.tileCount());
        assertEquals(1, h.server.acksOk());
        assertEquals(0, h.server.inFlight());
        TileKey k = h.client.lastTile();
        assertEquals(3, k.tileX()); assertEquals(-4, k.tileZ());
        assertTrue(TestTile.verify(3, -4, 1, 1, h.client.tile(k).decoded()));
        assertEquals(Provenance.AUTHORITATIVE, h.client.tile(k).provenance());
    }

    @Test void noTileBeforeHandshake() {
        Harness h = new Harness();
        assertEquals(ServerSession.SendResult.NOT_ACTIVE, h.sendTile(0, 0, 0, 1).result());
    }

    @Test void clientIgnoresTilesBeforeItIsActive() {
        Harness h = new Harness();
        Harness other = new Harness();
        other.handshake();
        byte[] tile = other.server.buildTestTile(1, 2, "minecraft:overworld", 0, 0, 0, 1, Provenance.AUTHORITATIVE).frame();
        assertTrue(h.client.onFrame(tile).isEmpty());            // IDLE
        h.client.start();
        assertTrue(h.client.onFrame(tile).isEmpty());            // HELLO_SENT, still not ACTIVE
        assertEquals(0, h.client.tileCount());
    }

    @Test void inFlightWindowIsBounded() {
        Harness h = new Harness();
        h.handshake();
        // Black-hole the client: tiles are built but never delivered, so nothing is acked.
        int sent = 0;
        for (int i = 0; i < 20; i++) {
            h.advanceMillis(1000);                                // keep the rate limiter out of the way
            if (h.server.buildTestTile(1, 2, "minecraft:overworld", i, 0, 0, 1, Provenance.AUTHORITATIVE).result() == ServerSession.SendResult.SENT) sent++;
        }
        assertEquals(h.lim.maxInFlightTiles(), sent);
        assertEquals(h.lim.maxInFlightTiles(), h.server.inFlight());
    }

    @Test void staleInFlightEntriesExpire() {
        Harness h = new Harness();
        h.handshake();
        for (int i = 0; i < h.lim.maxInFlightTiles(); i++) { h.advanceMillis(1000); h.server.buildTestTile(1, 2, "minecraft:overworld", i, 0, 0, 1, Provenance.AUTHORITATIVE); }
        assertEquals(ServerSession.SendResult.IN_FLIGHT_FULL, h.server.buildTestTile(1, 2, "minecraft:overworld", 99, 0, 0, 1, Provenance.AUTHORITATIVE).result());
        h.advanceMillis(31_000);
        assertEquals(ServerSession.SendResult.SENT, h.server.buildTestTile(1, 2, "minecraft:overworld", 99, 0, 0, 1, Provenance.AUTHORITATIVE).result());
    }

    @Test void tileRateLimitRecoversWithTime() {
        Harness h = new Harness();
        h.handshake();
        int sent = 0, limited = 0;
        for (int i = 0; i < 12; i++) {                            // no time passes: only the burst may go out
            ServerSession.TileSend s = h.sendTile(i, 0, 0, 1);
            if (s.result() == ServerSession.SendResult.SENT) sent++; else limited++;
        }
        assertEquals(h.lim.tileBurst(), sent);
        assertEquals(12 - h.lim.tileBurst(), limited);
        h.advanceMillis(1000);
        assertEquals(ServerSession.SendResult.SENT, h.sendTile(100, 0, 0, 1).result());
    }

    @Test void newerRevisionWinsAndOlderNeverOverwrites() {
        Harness h = new Harness();
        h.handshake();
        h.sendTile(1, 1, 0, 5);
        TileKey k = h.client.lastTile();
        h.advanceMillis(1000);
        h.sendTile(1, 1, 0, 3);                                   // older arrives later (out of order)
        assertEquals(5L, h.client.tile(k).revision());
        assertEquals(1, h.sc.get("server.ack.invalid") + h.cc.get("client.tile.stale"));   // exactly one stale was seen
        h.advanceMillis(1000);
        h.sendTile(1, 1, 0, 5);                                   // same revision again
        assertEquals(1, h.cc.get("client.tile.duplicate"));
        h.advanceMillis(1000);
        h.sendTile(1, 1, 0, 8);
        assertEquals(8L, h.client.tile(k).revision());
        assertEquals(1, h.client.tileCount());
        assertEquals(0, h.server.inFlight());                     // stale/duplicate are acked too, so the window never leaks
    }

    @Test void duplicateAndUnsolicitedAcksAreIgnored() {
        Harness h = new Harness();
        h.handshake();
        ServerSession.TileSend s = h.sendTile(2, 2, 0, 1);
        byte[] dup = Frame.encode(MessageType.TILE_ACK, 50, new TileAck(s.seq(), TileAck.Status.OK, 2, 2, 0, 1).encode(), 4096);
        h.server.onFrame(dup);                                    // already acked
        byte[] fake = Frame.encode(MessageType.TILE_ACK, 51, new TileAck(777, TileAck.Status.OK, 2, 2, 0, 1).encode(), 4096);
        h.server.onFrame(fake);                                   // never sent
        assertEquals(1, h.server.acksOk());
        assertEquals(2, h.sc.get("server.ack.unmatched"));
    }

    @Test void ackThatEchoesWrongFieldsIsRejected() {
        Harness h = new Harness();
        h.handshake();
        ServerSession.TileSend s = h.server.buildTestTile(1, 2, "minecraft:overworld", 4, 4, 0, 1, Provenance.AUTHORITATIVE);
        byte[] lie = Frame.encode(MessageType.TILE_ACK, 60, new TileAck(s.seq(), TileAck.Status.OK, 9, 9, 0, 1).encode(), 4096);
        h.server.onFrame(lie);
        assertEquals(1, h.sc.get("server.ack.mismatch"));
        assertEquals(1, h.server.inFlight());
    }

    @Test void tamperedContentIsReportedInvalid() {
        Harness h = new Harness();
        h.handshake();
        // a structurally perfect tile whose heights are not what the generator would produce
        byte[] wrong = TestTile.generate(0, 0, 0, 1); wrong[3] ^= 0x10;
        TileMessage tm = TileMessage.create(1, 2, "minecraft:overworld", 0, 0, 0, 1, Provenance.AUTHORITATIVE, Encoding.DEFLATE, wrong);
        byte[] frame = Frame.encode(MessageType.TILE, 5, tm.encode(), 16000);
        var acks = h.client.onFrame(frame);
        assertEquals(1, acks.size());
        assertEquals(TileAck.Status.INVALID, TileAck.decode(Frame.decode(acks.get(0), 4096).body()).status());
        assertEquals(0, h.client.tileCount());
    }

    @Test void clientStoreIsBounded() {
        Limits l = new Limits(16384, 65536, 8, 1_875_000, 4, 3, 0.1, 4, 2.0, 5, 4, 40, 5);   // cache of 4 tiles
        Harness h = new Harness(l);
        h.handshake();
        for (int i = 0; i < 25; i++) { h.advanceMillis(1000); h.sendTile(i, 0, 0, 1); }
        assertEquals(4, h.client.tileCount());
    }

    @Test void repeatedGarbageBlocksTheSessionThenEverythingIsDropped() {
        Harness h = new Harness();
        for (int i = 0; i < h.lim.strikeLimit(); i++) assertTrue(h.server.onFrame(new byte[]{1, 2, 3}).isEmpty());
        assertEquals(ServerSession.State.BLOCKED, h.server.state());
        byte[] hello = Frame.encode(MessageType.HELLO, 1, new Hello(1, 1, 1, 8192, "x").encode(), 4096);
        assertTrue(h.server.onFrame(hello).isEmpty());            // even a perfectly valid HELLO gets no answer any more
        assertEquals(ServerSession.State.BLOCKED, h.server.state());
    }

    @Test void silentServerMakesTheClientFallBackToVanilla() {
        Harness h = new Harness();
        h.client.start();
        int retries = 0;
        for (int t = 0; t < 10_000 && h.client.state() == ClientSession.State.HELLO_SENT; t++) if (h.client.tick() != null) retries++;
        assertEquals(ClientSession.State.FAILED, h.client.state());
        assertEquals(h.lim.helloMaxTries() - 1, retries);          // bounded retries, then it gives up
        assertTrue(h.client.reason().contains("vanilla fallback"));
        assertNull(h.client.tick());                               // and stays quiet
    }

    @Test void clientRecoversWhenTheFirstHelloIsLost() {
        Harness h = new Harness();
        h.client.start();                                          // first HELLO is lost
        byte[] retry = null;
        for (int t = 0; t < 1000 && retry == null; t++) retry = h.client.tick();
        assertNotNull(retry);
        h.toServer(retry);
        assertEquals(ClientSession.State.ACTIVE, h.client.state());
    }

    @Test void faultFramesNeverCrashAndNeverGrowMemory() {
        Harness h = new Harness();
        h.handshake();
        for (String kind : FaultInjector.KINDS) {
            for (int i = 0; i < 30; i++) {
                byte[] f = FaultInjector.build(kind, 1);
                if (f.length > Awre.HARD_MAX_FRAME) { h.client.onOversizeSkipped(f.length); continue; }   // what the codec does
                h.client.onFrame(f);
            }
        }
        assertEquals(ClientSession.State.FAILED, h.client.state()); // 150 malformed frames: it stops listening, it does not die
        assertEquals(0, h.client.tileCount());
    }

    @Test void eachFaultKindIsRejectedWithItsOwnReason() {
        String[][] expected = {{"crc", "BAD_CRC"}, {"truncated", "BAD_FIELD"}, {"bomb", "INFLATE_LIMIT"}, {"oversize", "TOO_LARGE"}};
        for (String[] e : expected) {
            Harness h = new Harness();
            h.handshake();
            h.client.onFrame(FaultInjector.build(e[0], 1));
            assertEquals(1, h.client.strikes(), e[0]);
            assertEquals(1, h.cc.get("client.frame.rejected." + e[1]), e[0] + " should be " + e[1]);
            assertEquals(0, h.client.tileCount(), e[0]);
            assertEquals(ClientSession.State.ACTIVE, h.client.state(), e[0] + ": one bad frame must not kill the session");
        }
    }
}
