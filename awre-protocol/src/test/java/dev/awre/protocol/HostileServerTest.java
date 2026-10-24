package dev.awre.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * A server the user connects to is not trusted. A server sending valid ~64 KB test tiles is not a protocol violation (no strike), so the
 * byte budget is the only defence: without it the client holds up to ~506 MiB at the 8192-tile cap (probe: 185 MiB after 3,000 tiles). The attacker here does not
 * rate-limit itself (the fake clock is advanced so the server-side token bucket never blocks); the client is the only defence under test.
 */
class HostileServerTest {
    private static final int SIDE = 180;                         // 64,800-byte payload, just under maxDecodedTileBytes (65,536)

    private static long held(Harness h) {
        long n = 0;
        for (TileKey k : h.client.tileKeys()) n += h.client.tile(k).decoded().length;
        return n;
    }

    private static void send(Harness h, int x, long rev) {
        h.advanceMillis(1000);
        for (byte[] f : h.server.sendTestTile(1, 2, "minecraft:overworld", x, 0, 0, rev, Provenance.AUTHORITATIVE, SIDE).frames()) h.toClient(f);
    }

    @Test void theClientNeverHoldsMoreDecodedBytesThanItsBudgetAndKeepsTheNewestTiles() {
        Harness h = new Harness(Limits.defaults().withClientTileCache(100_000));      // the count cap is deliberately not the binding limit
        h.client.setMaxStoreBytes(2L << 20);
        h.handshake();
        for (int i = 0; i < 300; i++) {
            send(h, i, 1);
            assertTrue(h.client.storeBytes() <= (2L << 20), "budget exceeded after tile " + i + ": " + h.client.storeBytes());
        }
        assertEquals(held(h), h.client.storeBytes(), "the byte accounting equals what is really held");
        assertTrue(h.client.tileCount() >= 30 && h.client.tileCount() <= 32, "2 MiB / 64.8 KB tiles, got " + h.client.tileCount());
        assertEquals(300 - h.client.tileCount(), h.cc.get("client.tile.evicted.bytes"));
        assertEquals(299, h.client.lastTile().tileX());
        assertNotNull(h.client.tile(h.client.lastTile()), "the newest tile is never evicted by its own insertion");
        assertEquals(0, h.client.strikes(), "a protocol-valid tile is not misbehaviour; the budget, not a strike, is the defence");
    }

    @Test void replacingATileAndDroppingTilesKeepsTheAccountingExact() {
        Harness h = new Harness(Limits.defaults().withClientTileCache(100_000));
        h.handshake();
        send(h, 7, 1);
        long one = h.client.storeBytes();
        assertEquals(SIDE * SIDE * 2, one);
        send(h, 7, 2);                                           // a newer revision replaces, it does not add
        assertEquals(one, h.client.storeBytes());
        assertEquals(1, h.client.tileCount());
        send(h, 8, 1);
        assertEquals(2 * one, h.client.storeBytes());
        send(h, 7, 1);                                           // stale revision: rejected, nothing changes
        assertEquals(2 * one, h.client.storeBytes());
        assertEquals(held(h), h.client.storeBytes());
    }

    @Test void theDefaultBudgetLeavesRoomForEveryRealTileAtTheDefaultCountCapAndBindsBeforeTheWorstCase() {
        int realTile = 1026;                                     // LodTileCodec v1 payload
        assertTrue(8192L * realTile * 4 < ClientSession.DEFAULT_MAX_STORE_BYTES, "8192 real tiles (even at 4x the size) fit the budget");
        assertTrue(ClientSession.DEFAULT_MAX_STORE_BYTES < 8192L * Limits.defaults().maxDecodedTileBytes(), "the default budget is below the count-cap-only worst case (8192 x 64 KiB = 512 MiB)");
        assertThrows(IllegalArgumentException.class, () -> new Harness().client.setMaxStoreBytes(1024));
        assertEquals(ClientSession.DEFAULT_MAX_STORE_BYTES, new Harness().client.maxStoreBytes());
    }
}
