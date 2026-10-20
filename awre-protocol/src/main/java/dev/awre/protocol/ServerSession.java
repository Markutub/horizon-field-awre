package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-player server state machine. Pure Java: no Bukkit types, deterministic with an injected clock.
 * It never throws on remote input, never answers malformed frames (no amplification) and bounds everything:
 * strikes, in-flight tiles, hello/tile rates.
 */
public final class ServerSession {
    public enum State { NEW, ACTIVE, REJECTED, BLOCKED }
    public enum SendResult { SENT, NOT_ACTIVE, RATE_LIMITED, IN_FLIGHT_FULL, TOO_LARGE }
    public record TileSend(SendResult result, byte[] frame, long seq) { }
    /** One tile as one or more frames (fragmented when it exceeds the negotiated frame size). ackSeq is the id the client acknowledges. */
    public record TileFrames(SendResult result, List<byte[]> frames, long ackSeq) { }

    private record Pending(int x, int z, int lod, long revision, long sentNanos) { }

    private static final long IN_FLIGHT_TIMEOUT_NANOS = 30_000_000_000L;

    private final Limits lim;
    private final LongSupplier nanos;
    private final Counters counters;
    private final long sessionId;
    private final TokenBucket helloBucket, tileBucket;
    private final Map<Long, Pending> inFlight = new LinkedHashMap<>();

    private State state = State.NEW;
    private int negotiatedVersion, negotiatedFrame, negotiatedCaps;
    private String clientModVersion = "";
    private long nextSeq = 1;
    private int strikes;
    private String lastError = "";
    private long tilesSent, acksOk, acksInvalid, acksStale;

    public ServerSession(Limits lim, LongSupplier nanos, Counters counters, long sessionId) {
        this.lim = lim; this.nanos = nanos; this.counters = counters; this.sessionId = sessionId;
        this.helloBucket = new TokenBucket(lim.helloBurst(), lim.helloRefillPerSec(), nanos);
        this.tileBucket = new TokenBucket(lim.tileBurst(), lim.tileRefillPerSec(), nanos);
        this.negotiatedFrame = lim.maxFrameBytes();
    }

    /** Handle one incoming plugin message. Returns frames to send back (possibly empty). Never throws. */
    public synchronized List<byte[]> onFrame(byte[] data) {
        if (state == State.BLOCKED || state == State.REJECTED) { counters.inc("server.frame.dropped." + state.name().toLowerCase()); return List.of(); }
        counters.add("server.bytes.in", data.length);
        try {
            Frame f = Frame.decode(data, state == State.ACTIVE ? negotiatedFrame : lim.maxFrameBytes());
            switch (f.type()) {
                case HELLO: return onHello(Hello.decode(f.body()));
                case TILE_ACK: onAck(TileAck.decode(f.body())); return List.of();
                default: throw new ProtocolException(Reason.UNKNOWN_TYPE, "not a server-bound type: " + f.type());
            }
        } catch (ProtocolException e) {
            return strike(e);
        }
    }

    private List<byte[]> strike(ProtocolException e) {
        strikes++;
        lastError = e.reason().name();
        counters.inc("server.frame.rejected." + e.reason().name());
        if (strikes >= lim.strikeLimit()) { state = State.BLOCKED; counters.inc("server.session.blocked"); }
        return List.of();
    }

    private List<byte[]> onHello(Hello h) {
        if (!helloBucket.tryAcquire()) { counters.inc("server.hello.ratelimited"); return List.of(); }
        clientModVersion = h.modVersion();
        if (state == State.ACTIVE) return List.of(ackFrame(HelloAck.Status.OK)); // idempotent: the client may have missed our ACK
        int lo = Math.max(h.minVersion(), Awre.PROTOCOL_MIN), hi = Math.min(h.maxVersion(), Awre.PROTOCOL_MAX);
        boolean capsOk = (h.caps() & Awre.CAP_TILE_V1) != 0;
        if (lo > hi || !capsOk) {
            state = State.REJECTED;
            lastError = lo > hi ? "INCOMPATIBLE_VERSION" : "INCOMPATIBLE_CAPS";
            counters.inc("server.hello.incompatible");
            return List.of(ackFrame(HelloAck.Status.INCOMPATIBLE));
        }
        negotiatedVersion = hi;
        negotiatedFrame = Math.min(lim.maxFrameBytes(), h.clientMaxFrame()); // client value is clamped, never trusted upward
        negotiatedCaps = h.caps() & Awre.CAPS_ALL;
        state = State.ACTIVE;
        counters.inc("server.hello.ok");
        return List.of(ackFrame(HelloAck.Status.OK));
    }

    private byte[] ackFrame(HelloAck.Status st) {
        boolean ok = st == HelloAck.Status.OK;
        HelloAck a = new HelloAck(st, ok ? negotiatedVersion : 0, Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX,
                ok ? negotiatedCaps : Awre.CAPS_ALL, ok ? negotiatedFrame : lim.maxFrameBytes(), lim.maxDecodedTileBytes(), sessionId);
        return frame(MessageType.HELLO_ACK, a.encode());
    }

    private byte[] frame(MessageType t, byte[] body) { return frame(t, body, lim.maxFrameBytes()); }

    private byte[] frame(MessageType t, byte[] body, int maxFrame) {
        byte[] f = Frame.encode(t, nextSeq, body, maxFrame);
        nextSeq = (nextSeq + 1) & 0xFFFFFFFFL;
        counters.add("server.bytes.out", f.length);
        return f;
    }

    /** Notified (under this session's lock: do not call back into the session) when a tile is acknowledged or expires. */
    public interface TileListener {
        void onAck(int tileX, int tileZ, int lod, long revision, TileAck.Status status);
        void onExpired(int tileX, int tileZ, int lod, long revision);
    }

    private TileListener listener;

    public synchronized void setTileListener(TileListener l) { this.listener = l; }

    /** Tell the client to forget tiles (needs CAP_DROP_V1; returns no frames otherwise). Splits into frames of at most 256 keys. */
    public synchronized List<byte[]> sendDrop(long worldMsb, long worldLsb, String dimension, List<TileDrop.Key> keys) {
        if (state != State.ACTIVE || (negotiatedCaps & Awre.CAP_DROP_V1) == 0 || keys.isEmpty()) return List.of();
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += Awre.MAX_DROP_KEYS) {
            List<TileDrop.Key> part = keys.subList(i, Math.min(keys.size(), i + Awre.MAX_DROP_KEYS));
            out.add(frame(MessageType.DROP, new TileDrop(worldMsb, worldLsb, dimension, part).encode(), negotiatedFrame));
            counters.add("server.drop.keys", part.size());
        }
        return out;
    }

    /** Append palette entries (ids startId, startId+1, ...) on the client; needs CAP_PALETTE_V1 (no frames otherwise). */
    public synchronized List<byte[]> sendPalette(long worldMsb, long worldLsb, String dimension, int startId, List<String> keys) {
        if (state != State.ACTIVE || (negotiatedCaps & Awre.CAP_PALETTE_V1) == 0 || keys.isEmpty()) return List.of();
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += Awre.MAX_PALETTE_PER_MESSAGE) {
            List<String> part = keys.subList(i, Math.min(keys.size(), i + Awre.MAX_PALETTE_PER_MESSAGE));
            out.add(frame(MessageType.PALETTE, new PaletteUpdate(worldMsb, worldLsb, dimension, startId + i, part).encode(), negotiatedFrame));
            counters.add("server.palette.keys", part.size());
        }
        return out;
    }

    public int maxInFlight() { return lim.maxInFlightTiles(); }
    public synchronized int negotiatedCaps() { return negotiatedCaps; }

    private void onAck(TileAck a) {
        if (state != State.ACTIVE) { counters.inc("server.ack.unexpected"); return; }
        Pending p = inFlight.get(a.ackSeq());
        if (p == null) { counters.inc("server.ack.unmatched"); return; } // duplicate or unsolicited
        if (p.x != a.tileX() || p.z != a.tileZ() || p.lod != a.lod() || p.revision != a.revision()) { counters.inc("server.ack.mismatch"); return; }
        inFlight.remove(a.ackSeq());
        if (listener != null) listener.onAck(a.tileX(), a.tileZ(), a.lod(), a.revision(), a.status());
        switch (a.status()) {
            case OK -> acksOk++;
            case INVALID -> { acksInvalid++; counters.inc("server.ack.invalid"); }
            default -> acksStale++;
        }
    }

    /** Build one G0 test tile (or explain why not). Arguments come from server code and are validated like remote data. */
    public synchronized TileSend buildTestTile(long worldMsb, long worldLsb, String dimension, int tileX, int tileZ, int lod,
                                               long revision, Provenance prov) {
        if (state != State.ACTIVE) return new TileSend(SendResult.NOT_ACTIVE, null, 0);
        purgeExpired();
        if (inFlight.size() >= lim.maxInFlightTiles()) { counters.inc("server.tile.inflightfull"); return new TileSend(SendResult.IN_FLIGHT_FULL, null, 0); }
        if (!tileBucket.tryAcquire()) { counters.inc("server.tile.ratelimited"); return new TileSend(SendResult.RATE_LIMITED, null, 0); }
        TileMessage tm = TileMessage.create(worldMsb, worldLsb, dimension, tileX, tileZ, lod, revision, prov, Encoding.DEFLATE,
                TestTile.generate(tileX, tileZ, lod, revision));
        byte[] f = frame(MessageType.TILE, tm.encode()); // throws IllegalArgumentException if it cannot fit one frame (no fragmentation in G0)
        long seq = (nextSeq - 1) & 0xFFFFFFFFL;
        inFlight.put(seq, new Pending(tileX, tileZ, lod, revision, nanos.getAsLong()));
        tilesSent++;
        counters.inc("server.tile.sent");
        return new TileSend(SendResult.SENT, f, seq);
    }

    /**
     * Send any tile: one TILE frame when it fits the NEGOTIATED frame size, else FRAGMENT frames when the client negotiated
     * CAP_FRAGMENT_V1, else TOO_LARGE. Same in-flight and rate limits as {@link #buildTestTile}.
     * Note: frames here use the negotiated size (min of both sides), unlike the G0 test path which used the server limit.
     */
    public synchronized TileFrames sendTile(TileMessage tm) {
        if (state != State.ACTIVE) return new TileFrames(SendResult.NOT_ACTIVE, List.of(), 0);
        if (tm.maxDecodedLen() > lim.maxDecodedTileBytes()) return new TileFrames(SendResult.TOO_LARGE, List.of(), 0);
        byte[] body = tm.encode();
        boolean single = body.length + Frame.OVERHEAD <= negotiatedFrame;
        boolean canFragment = (negotiatedCaps & Awre.CAP_FRAGMENT_V1) != 0;
        if (!single && (!canFragment || Fragmenter.fragmentCount(body.length, negotiatedFrame) > Awre.MAX_FRAGMENTS)) {
            counters.inc("server.tile.toolarge");
            return new TileFrames(SendResult.TOO_LARGE, List.of(), 0);
        }
        purgeExpired();
        if (inFlight.size() >= lim.maxInFlightTiles()) { counters.inc("server.tile.inflightfull"); return new TileFrames(SendResult.IN_FLIGHT_FULL, List.of(), 0); }
        if (!tileBucket.tryAcquire()) { counters.inc("server.tile.ratelimited"); return new TileFrames(SendResult.RATE_LIMITED, List.of(), 0); }
        List<byte[]> frames = new ArrayList<>();
        long ackSeq = nextSeq;
        if (single) {
            frames.add(frame(MessageType.TILE, body, negotiatedFrame));
        } else {
            for (Fragment fr : Fragmenter.split(body, ackSeq, negotiatedFrame)) frames.add(frame(MessageType.FRAGMENT, fr.encode(), negotiatedFrame));
            counters.inc("server.tile.fragmented");
            counters.add("server.fragments.sent", frames.size());
        }
        inFlight.put(ackSeq, new Pending(tm.tileX(), tm.tileZ(), tm.lod(), tm.revision(), nanos.getAsLong()));
        tilesSent++;
        counters.inc("server.tile.sent");
        return new TileFrames(SendResult.SENT, frames, ackSeq);
    }

    /** Convenience for tests and /awre fragtest: a deterministic noise tile of side x side int16 samples (side 16 uses the G0 pattern). */
    public synchronized TileFrames sendTestTile(long worldMsb, long worldLsb, String dimension, int tileX, int tileZ, int lod,
                                                long revision, Provenance prov, int side) {
        byte[] decoded = side == TestTile.SIDE ? TestTile.generate(tileX, tileZ, lod, revision) : TestTile.generateNoise(tileX, tileZ, lod, revision, side);
        return sendTile(TileMessage.create(worldMsb, worldLsb, dimension, tileX, tileZ, lod, revision, prov, Encoding.DEFLATE, decoded));
    }

    /** Drop (and report to the listener) tiles unacknowledged for too long. Callers that stop sending when the window is full MUST call this. */
    public synchronized int expireStale() {
        int before = inFlight.size();
        purgeExpired();
        return before - inFlight.size();
    }

    private void purgeExpired() {
        long now = nanos.getAsLong();
        for (Iterator<Pending> it = inFlight.values().iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (now - p.sentNanos > IN_FLIGHT_TIMEOUT_NANOS) {
                it.remove(); counters.inc("server.tile.expired");
                if (listener != null) listener.onExpired(p.x, p.z, p.lod, p.revision);
            }
        }
    }

    // ---- diagnostics (read by /awre status) ----
    public synchronized State state() { return state; }
    public synchronized int negotiatedVersion() { return negotiatedVersion; }
    public synchronized int negotiatedFrame() { return negotiatedFrame; }
    public synchronized String clientModVersion() { return clientModVersion; }
    public synchronized int strikes() { return strikes; }
    public synchronized int inFlight() { return inFlight.size(); }
    public synchronized String lastError() { return lastError; }
    public synchronized long tilesSent() { return tilesSent; }
    public synchronized long acksOk() { return acksOk; }
    public synchronized long acksInvalid() { return acksInvalid; }
    public synchronized long acksStale() { return acksStale; }
    public long sessionId() { return sessionId; }

    /** Frames for the bounded-decode test; they bypass tile bookkeeping on purpose (they are not tiles). */
    public synchronized List<byte[]> faultFrames(String kind) {
        List<byte[]> l = new ArrayList<>();
        if (state != State.ACTIVE) return l;
        l.add(FaultInjector.build(kind, nextSeq));
        return l;
    }
}
