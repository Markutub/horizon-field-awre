package dev.awre.protocol;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DropTest {
    private static final String DIM = "minecraft:overworld";

    private static TileDrop drop(int n) {
        List<TileDrop.Key> k = new ArrayList<>();
        for (int i = 0; i < n; i++) k.add(new TileDrop.Key(i, -i, i % 4));
        return new TileDrop(1, 2, DIM, k);
    }

    @Test void roundTrip() {
        TileDrop d = drop(10);
        assertEquals(d, TileDrop.decode(d.encode(), Limits.defaults()));
    }

    @Test void decodeRejectsBadBodies() {
        Limits lim = Limits.defaults();
        byte[] ok = drop(3).encode();
        assertEquals(Reason.TRAILING_BYTES, assertThrows(ProtocolException.class, () -> TileDrop.decode(java.util.Arrays.copyOf(ok, ok.length + 1), lim)).reason());
        assertThrows(ProtocolException.class, () -> TileDrop.decode(java.util.Arrays.copyOf(ok, ok.length - 1), lim));
        byte[] zero = new ByteWriter().i64(1).i64(2).string(DIM, 48).u16(0).toByteArray();
        assertEquals(Reason.OUT_OF_RANGE, assertThrows(ProtocolException.class, () -> TileDrop.decode(zero, lim)).reason());
        byte[] many = new ByteWriter().i64(1).i64(2).string(DIM, 48).u16(257).toByteArray();
        assertEquals(Reason.OUT_OF_RANGE, assertThrows(ProtocolException.class, () -> TileDrop.decode(many, lim)).reason());
        byte[] lod = new ByteWriter().i64(1).i64(2).string(DIM, 48).u16(1).i32(0).i32(0).u8(lim.maxLod() + 1).toByteArray();
        assertEquals(Reason.OUT_OF_RANGE, assertThrows(ProtocolException.class, () -> TileDrop.decode(lod, lim)).reason());
        byte[] far = new ByteWriter().i64(1).i64(2).string(DIM, 48).u16(1).i32((int) lim.maxTileCoord() + 1).i32(0).u8(0).toByteArray();
        assertEquals(Reason.OUT_OF_RANGE, assertThrows(ProtocolException.class, () -> TileDrop.decode(far, lim)).reason());
        byte[] noDim = new ByteWriter().i64(1).i64(2).string("", 48).u16(1).i32(0).i32(0).u8(0).toByteArray();
        assertEquals(Reason.BAD_FIELD, assertThrows(ProtocolException.class, () -> TileDrop.decode(noDim, lim)).reason());
    }

    @Test void clientForgetsDroppedTilesOnly() {
        Harness h = new Harness();
        h.handshake();
        for (int i = 0; i < 4; i++) h.sendTile(i, 0, 0, 1);
        assertEquals(4, h.client.tileCount());
        TileDrop d = new TileDrop(1, 2, DIM, List.of(new TileDrop.Key(1, 0, 0), new TileDrop.Key(2, 0, 0), new TileDrop.Key(99, 99, 0)));
        for (byte[] f : h.server.sendDrop(1, 2, DIM, d.keys())) h.toClient(f);
        assertEquals(2, h.client.tileCount());
        assertNull(h.client.tile(new TileKey(1, 2, DIM, 1, 0, 0)));
        assertNotNull(h.client.tile(new TileKey(1, 2, DIM, 0, 0, 0)));
        assertEquals(2, h.cc.get("client.tile.dropped"));
        assertEquals(0, h.client.strikes());
        // other world: no effect
        for (byte[] f : h.server.sendDrop(9, 9, DIM, List.of(new TileDrop.Key(0, 0, 0)))) h.toClient(f);
        assertEquals(2, h.client.tileCount());
    }

    @Test void largeDropsAreSplit() {
        Harness h = new Harness();
        h.handshake();
        List<byte[]> frames = h.server.sendDrop(1, 2, DIM, drop(600).keys());
        assertEquals(3, frames.size());
        for (byte[] f : frames) assertTrue(f.length <= h.lim.maxFrameBytes());
    }

    @Test void noDropWithoutTheCapabilityOrBeforeActive() {
        Harness h = new Harness();
        assertTrue(h.server.sendDrop(1, 2, DIM, drop(1).keys()).isEmpty());             // not ACTIVE yet
        h.server.onFrame(Frame.encode(MessageType.HELLO, 1, new Hello(1, 1, Awre.CAP_TILE_V1, 16384, "old").encode(), 16384));
        assertTrue(h.server.sendDrop(1, 2, DIM, drop(1).keys()).isEmpty());             // client did not negotiate DROP
    }

    @Test void listenerSeesAcksAndExpiries() {
        Harness h = new Harness();
        h.handshake();
        List<String> events = new ArrayList<>();
        h.server.setTileListener(new ServerSession.TileListener() {
            @Override public void onAck(int x, int z, int lod, long rev, TileAck.Status st) { events.add("ack " + x + "," + z + "," + lod + "," + rev + "," + st); }
            @Override public void onExpired(int x, int z, int lod, long rev) { events.add("exp " + x + "," + z + "," + lod + "," + rev); }
        });
        h.sendTile(3, 4, 1, 7);
        assertEquals(List.of("ack 3,4,1,7,OK"), events);
        ServerSession.TileSend lost = h.server.buildTestTile(1, 2, DIM, 5, 6, 0, 8, Provenance.AUTHORITATIVE);   // never delivered
        assertNotNull(lost.frame());
        h.advanceMillis(31_000);
        h.sendTile(9, 9, 0, 1);                                                                                   // purge runs on the next send
        assertEquals("exp 5,6,0,8", events.get(1));
    }
}
