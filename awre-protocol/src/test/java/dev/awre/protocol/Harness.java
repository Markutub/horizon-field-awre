package dev.awre.protocol;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory "network" between one ServerSession and one ClientSession with a fake clock. Not a test class (no Test suffix). */
final class Harness {
    final AtomicLong clock = new AtomicLong(1_000_000_000L);
    final Counters sc = new Counters(), cc = new Counters();
    final Limits lim;
    final ServerSession server;
    final ClientSession client;

    Harness() { this(Limits.defaults()); }

    Harness(Limits lim) { this(lim, Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX); }

    Harness(Limits lim, int clientMin, int clientMax) {
        this.lim = lim;
        server = new ServerSession(lim, clock::get, sc, 0xABCDEF01L);
        client = new ClientSession(lim, cc, "test-1.0", clientMin, clientMax);
    }

    void advanceMillis(long ms) { clock.addAndGet(ms * 1_000_000L); }

    /** Run a full client-initiated handshake. */
    void handshake() { toServer(client.start()); }

    void toServer(byte[] frame) { for (byte[] r : server.onFrame(frame)) toClient(r); }

    void toClient(byte[] frame) { for (byte[] r : client.onFrame(frame)) toServer(r); }

    ServerSession.TileSend sendTile(int x, int z, int lod, long rev) {
        ServerSession.TileSend s = server.buildTestTile(1, 2, "minecraft:overworld", x, z, lod, rev, Provenance.AUTHORITATIVE);
        if (s.frame() != null) toClient(s.frame());
        return s;
    }
}
