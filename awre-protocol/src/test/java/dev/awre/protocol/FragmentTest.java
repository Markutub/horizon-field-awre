package dev.awre.protocol;

import static org.junit.jupiter.api.Assertions.*;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FragmentTest {
    private static byte[] noise(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    @Test void fragmentCountMatchesTheFormula() {
        int chunk = 16384 - Frame.OVERHEAD - Awre.FRAGMENT_HEADER;
        assertEquals(16384 - 14 - 18, chunk);
        assertEquals(2, Fragmenter.fragmentCount(chunk + 1, 16384));
        assertEquals(2, Fragmenter.fragmentCount(2 * chunk, 16384));
        assertEquals(3, Fragmenter.fragmentCount(2 * chunk + 1, 16384));
        for (int len : new int[]{chunk + 1, 3 * chunk - 5, 10 * chunk}) {
            assertEquals(Fragmenter.fragmentCount(len, 16384), Fragmenter.split(noise(len, len), 1, 16384).size());
        }
    }

    @Test void everySliceFitsOneFrame() {
        for (Fragment f : Fragmenter.split(noise(100_000, 3), 9, 16384)) {
            assertTrue(Frame.encode(MessageType.FRAGMENT, 1, f.encode(), 16384).length <= 16384);
        }
    }

    @Test void roundTripInAnyOrder() {
        Random rnd = new Random(7);
        for (int len : new int[]{5000, 16353, 16355, 40_000, 90_000}) {
            byte[] body = noise(len, len);
            if (Fragmenter.fragmentCount(len, 16384) < 2) { assertThrows(IllegalArgumentException.class, () -> Fragmenter.split(body, 5, 16384)); continue; }
            List<Fragment> parts = new ArrayList<>(Fragmenter.split(body, 5, 16384));
            Collections.shuffle(parts, rnd);
            Reassembler r = new Reassembler(new Counters());
            byte[] out = null;
            for (int i = 0; i < parts.size(); i++) {
                Fragment decoded = Fragment.decode(parts.get(i).encode(), 1 << 20);
                byte[] res = r.accept(decoded);
                if (i < parts.size() - 1) assertNull(res); else out = res;
            }
            assertArrayEquals(body, out, "len " + len);
            assertEquals(0, r.activeCount());
        }
    }

    @Test void duplicatesAreIgnored() {
        byte[] body = noise(40_000, 1);
        List<Fragment> parts = Fragmenter.split(body, 1, 16384);
        Counters c = new Counters();
        Reassembler r = new Reassembler(c);
        assertNull(r.accept(parts.get(0)));
        assertNull(r.accept(parts.get(0)));
        assertNull(r.accept(parts.get(1)));
        assertArrayEquals(body, r.accept(parts.get(2)));
        assertEquals(1, c.get("reasm.duplicate"));
    }

    @Test void corruptedSliceFailsTheWholeCrc() {
        byte[] body = noise(40_000, 2);
        List<Fragment> parts = new ArrayList<>(Fragmenter.split(body, 1, 16384));
        Fragment bad = parts.get(1);
        byte[] d = bad.data().clone(); d[10] ^= 1;
        parts.set(1, new Fragment(bad.msgId(), bad.index(), bad.count(), bad.totalLen(), bad.wholeCrc(), bad.chunkSize(), d));
        Reassembler r = new Reassembler(new Counters());
        r.accept(parts.get(0)); r.accept(parts.get(1));
        assertEquals(Reason.CHECKSUM_MISMATCH, assertThrows(ProtocolException.class, () -> r.accept(parts.get(2))).reason());
    }

    private static Reason decodeReason(Fragment f, int max) {
        return assertThrows(ProtocolException.class, () -> Fragment.decode(f.encode(), max)).reason();
    }

    @Test void headerBoundsAreEnforcedBeforeAllocation() {
        byte[] d = new byte[100];
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(new Fragment(1, 0, 1, 100, 0, 100, d), 1 << 20));             // count < 2
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(new Fragment(1, 0, 65, 6400, 0, 100, d), 1 << 20));            // count bomb
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(new Fragment(1, 2, 2, 150, 0, 100, d), 1 << 20));             // index >= count
        assertEquals(Reason.OUT_OF_RANGE, decodeReason(new Fragment(1, 0, 2, 150, 0, 100, d), 100));                 // total > cap
        assertEquals(Reason.BAD_FIELD, decodeReason(new Fragment(1, 0, 2, 300, 0, 100, d), 1 << 20));                // total > count*chunk
        assertEquals(Reason.BAD_FIELD, decodeReason(new Fragment(1, 0, 3, 100, 0, 100, d), 1 << 20));                // total <= (count-1)*chunk
        assertEquals(Reason.TRUNCATED, decodeReason(new Fragment(1, 0, 2, 150, 0, 100, new byte[99]), 1 << 20));     // short data
        assertEquals(Reason.TRAILING_BYTES, decodeReason(new Fragment(1, 1, 2, 150, 0, 100, new byte[51]), 1 << 20)); // long last slice
    }

    @Test void headerChangeMidTileDropsTheAssembly() {
        List<Fragment> a = Fragmenter.split(noise(40_000, 1), 1, 16384);
        List<Fragment> b = Fragmenter.split(noise(40_000, 2), 1, 16384); // same msgId, different crc
        Reassembler r = new Reassembler(new Counters());
        r.accept(a.get(0));
        assertEquals(Reason.BAD_FIELD, assertThrows(ProtocolException.class, () -> r.accept(b.get(1))).reason());
        assertEquals(0, r.activeCount());
    }

    @Test void concurrentAssembliesAreBoundedAndOldestIsEvicted() {
        Counters c = new Counters();
        Reassembler r = new Reassembler(c);
        for (long id = 1; id <= 5; id++) r.accept(Fragmenter.split(noise(40_000, id), id, 16384).get(0));
        assertEquals(Awre.MAX_ASSEMBLIES, r.activeCount());
        assertEquals(3, c.get("reasm.evicted"));
    }

    @Test void stalledAssembliesTimeOut() {
        Counters c = new Counters();
        Reassembler r = new Reassembler(c);
        r.accept(Fragmenter.split(noise(40_000, 1), 1, 16384).get(0));
        for (int i = 0; i < Awre.ASSEMBLY_TIMEOUT_TICKS; i++) r.tick();
        assertEquals(1, r.activeCount());
        r.tick();
        assertEquals(0, r.activeCount());
        assertEquals(1, c.get("reasm.timeout"));
    }

    // ---- end to end through the real sessions ----

    @Test void largeTileTravelsFragmentedAndIsAcknowledgedOnce() {
        Harness h = new Harness();
        h.handshake();
        assertEquals(Awre.CAPS_ALL, h.client.negotiatedCaps());
        ServerSession.TileFrames tf = h.server.sendTestTile(1, 2, "minecraft:overworld", 7, -3, 2, 5, Provenance.AUTHORITATIVE, 128); // 32 KiB of noise
        assertEquals(ServerSession.SendResult.SENT, tf.result());
        assertTrue(tf.frames().size() >= 2, "expected fragmentation, got " + tf.frames().size() + " frame(s)");
        for (byte[] f : tf.frames()) assertTrue(f.length <= h.lim.maxFrameBytes());
        assertEquals(1, h.server.inFlight());
        for (byte[] f : tf.frames()) h.toClient(f);
        assertEquals(0, h.server.inFlight());
        assertEquals(1, h.server.acksOk());
        assertEquals(1, h.client.tileCount());
        TileKey k = new TileKey(1, 2, "minecraft:overworld", 7, -3, 2);
        assertArrayEquals(TestTile.generateNoise(7, -3, 2, 5, 128), h.client.tile(k).decoded());
        assertEquals(0, h.client.strikes());
    }

    @Test void smallTilesStillUseOneFrame() {
        Harness h = new Harness();
        h.handshake();
        ServerSession.TileFrames tf = h.server.sendTestTile(1, 2, "d", 1, 1, 0, 1, Provenance.AUTHORITATIVE, 16);
        assertEquals(1, tf.frames().size());
        assertEquals(MessageType.TILE, Frame.decode(tf.frames().get(0), 16384).type());
    }

    @Test void clientWithoutFragmentCapGetsTooLargeInsteadOfBrokenFrames() {
        Harness h = new Harness();
        byte[] hello = Frame.encode(MessageType.HELLO, 1, new Hello(1, 1, Awre.CAP_TILE_V1, 16384, "old").encode(), 16384);
        h.server.onFrame(hello);
        assertEquals(ServerSession.State.ACTIVE, h.server.state());
        assertEquals(ServerSession.SendResult.TOO_LARGE,
                h.server.sendTestTile(1, 2, "d", 1, 1, 0, 1, Provenance.AUTHORITATIVE, 128).result());
        assertEquals(0, h.server.inFlight());
    }

    @Test void fragmentFramesAreStrikesBeforeHandshake() {
        Harness h = new Harness();
        Fragment f = Fragmenter.split(noise(40_000, 1), 1, 16384).get(0);
        // client not started: state IDLE -> dropped, no allocation
        assertTrue(h.client.onFrame(Frame.encode(MessageType.FRAGMENT, 1, f.encode(), 16384)).isEmpty());
        assertEquals(0, h.client.partialTiles());
    }

    @Test void fragmentFloodCannotGrowMemoryOrBypassStrikes() {
        Harness h = new Harness();
        h.handshake();
        // 1000 distinct one-slice "tiles": only MAX_ASSEMBLIES partials can exist at once
        for (long id = 1; id <= 1000; id++) {
            Fragment f = Fragmenter.split(noise(40_000, id), id, 16384).get(0);
            h.client.onFrame(Frame.encode(MessageType.FRAGMENT, id, f.encode(), 16384));
        }
        assertTrue(h.client.partialTiles() <= Awre.MAX_ASSEMBLIES);
        assertEquals(0, h.client.tileCount());
    }

    @Test void oversizedFragmentTotalIsRejectedAsAStrike() {
        Harness h = new Harness();
        h.handshake();
        Fragment bomb = new Fragment(1, 0, 64, 64 * 16000, 0, 16000, new byte[16000]); // ~1 MB total, above the negotiated cap
        h.client.onFrame(Frame.encode(MessageType.FRAGMENT, 1, bomb.encode(), 16384));
        assertEquals(1, h.client.strikes());
        assertEquals(0, h.client.partialTiles());
    }
}
