package dev.awre.protocol;

import dev.awre.protocol.ProtocolException.Reason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side state machine (pure Java, tick driven, no Minecraft types). One instance per connection.
 * Validates every received byte through the same bounded decoders as the server and keeps a bounded tile store in which
 * a newer revision always wins and an older one never overwrites it.
 */
public final class ClientSession {
    public enum State { IDLE, HELLO_SENT, ACTIVE, INCOMPATIBLE, FAILED }
    public record Received(long revision, Provenance provenance, byte[] decoded) { }

    private final Limits lim;
    private final Counters counters;
    private final String modVersion;
    private final int minVersion, maxVersion;
    private final Map<TileKey, Received> store;
    private final Reassembler reassembler;
    private record PaletteKey(long msb, long lsb, String dimension) { }
    private final Map<PaletteKey, List<String>> palettes = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<PaletteKey, List<String>> e) { return size() > Awre.MAX_CLIENT_PALETTES; }
    };
    private volatile PayloadValidator validator = PayloadValidator.TEST_TILES;

    private State state = State.IDLE;
    private int ticks, tries, strikes;
    private int negotiatedVersion, negotiatedFrame, negotiatedMaxDecoded, negotiatedCaps;
    private long sessionId, nextSeq = 1;
    private String reason = "";
    private String lastServerError = "";
    private TileKey lastTile;

    public ClientSession(Limits lim, Counters counters, String modVersion) {
        this(lim, counters, modVersion, Awre.PROTOCOL_MIN, Awre.PROTOCOL_MAX);
    }

    public ClientSession(Limits lim, Counters counters, String modVersion, int minVersion, int maxVersion) {
        this.lim = lim; this.counters = counters; this.modVersion = modVersion; this.minVersion = minVersion; this.maxVersion = maxVersion;
        this.negotiatedFrame = lim.maxFrameBytes();
        this.reassembler = new Reassembler(counters);
        final int cap = lim.clientTileCache();
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<TileKey, Received> e) { return size() > cap; }
        };
    }

    private byte[] helloFrame() {
        byte[] body = new Hello(minVersion, maxVersion, Awre.CAPS_ALL, lim.maxFrameBytes(), modVersion).encode();
        return out(MessageType.HELLO, body);
    }

    private byte[] out(MessageType t, byte[] body) {
        byte[] f = Frame.encode(t, nextSeq, body, lim.maxFrameBytes());
        nextSeq = (nextSeq + 1) & 0xFFFFFFFFL;
        counters.add("client.bytes.out", f.length);
        return f;
    }

    /** Begin negotiation; returns the first HELLO. */
    public synchronized byte[] start() {
        state = State.HELLO_SENT; tries = 1; ticks = 0;
        return helloFrame();
    }

    /** Call once per client tick. Returns a HELLO retry frame, or null. Gives up (vanilla fallback) after helloMaxTries. */
    public synchronized byte[] tick() {
        reassembler.tick();
        if (state != State.HELLO_SENT) return null;
        if (++ticks < lim.helloRetryTicks()) return null;
        ticks = 0;
        if (tries >= lim.helloMaxTries()) {
            state = State.FAILED;
            reason = "no AWRE response after " + tries + " HELLOs (server has no AWRE or does not forward the channel): vanilla fallback";
            counters.inc("client.handshake.timeout");
            return null;
        }
        tries++;
        counters.inc("client.hello.retry");
        return helloFrame();
    }

    /** A payload larger than the codec-level hard limit was skipped without allocation. */
    public synchronized void onOversizeSkipped(int length) {
        counters.inc("client.frame.skipped.huge");
        strike(new ProtocolException(Reason.TOO_LARGE, "skipped " + length));
    }

    /** Handle one received frame. Returns frames to send back. Never throws. */
    public synchronized List<byte[]> onFrame(byte[] data) {
        if (state == State.IDLE || state == State.FAILED || state == State.INCOMPATIBLE) { counters.inc("client.frame.dropped.state"); return List.of(); }
        counters.add("client.bytes.in", data.length);
        try {
            Frame f = Frame.decode(data, state == State.ACTIVE ? negotiatedFrame : lim.maxFrameBytes());
            switch (f.type()) {
                case HELLO_ACK: onHelloAck(HelloAck.decode(f.body())); return List.of();
                case TILE: return onTile(f);
                case FRAGMENT: return onFragment(f);
                case DROP: return onDrop(f);
                case PALETTE: return onPalette(f);
                case ERROR: lastServerError = ErrorMessage.decode(f.body()).detail(); counters.inc("client.server.error"); return List.of();
                default: throw new ProtocolException(Reason.UNKNOWN_TYPE, "not a client-bound type: " + f.type());
            }
        } catch (ProtocolException e) {
            strike(e);
            return List.of();
        }
    }

    private void strike(ProtocolException e) {
        strikes++;
        counters.inc("client.frame.rejected." + e.reason().name());
        reason = e.reason().name();
        if (strikes >= lim.strikeLimit()) { state = State.FAILED; reason = "too many malformed frames (" + reason + ")"; }
    }

    private void onHelloAck(HelloAck a) {
        if (state != State.HELLO_SENT) { counters.inc("client.ack.unexpected"); return; }
        if (a.status() != HelloAck.Status.OK) {
            state = State.INCOMPATIBLE;
            reason = "server says " + a.status() + " (server supports v" + a.serverMin() + ".." + a.serverMax() + ", client v" + minVersion + ".." + maxVersion + ")";
            counters.inc("client.handshake.incompatible");
            return;
        }
        if (a.selectedVersion() < minVersion || a.selectedVersion() > maxVersion || (a.caps() & Awre.CAP_TILE_V1) == 0 || a.maxFrame() < 256) {
            state = State.FAILED; reason = "server selected an unusable version/caps"; return;
        }
        negotiatedVersion = a.selectedVersion();
        negotiatedFrame = Math.min(a.maxFrame(), lim.maxFrameBytes());
        negotiatedMaxDecoded = Math.min(a.maxDecodedTile(), lim.maxDecodedTileBytes());
        negotiatedCaps = a.caps() & Awre.CAPS_ALL;
        sessionId = a.sessionId();
        state = State.ACTIVE;
        counters.inc("client.handshake.ok");
    }

    private List<byte[]> onPalette(Frame f) {
        if (state != State.ACTIVE) { counters.inc("client.tile.unexpected"); return List.of(); }
        if ((negotiatedCaps & Awre.CAP_PALETTE_V1) == 0) throw new ProtocolException(Reason.UNKNOWN_TYPE, "PALETTE without negotiated capability");
        PaletteUpdate u = PaletteUpdate.decode(f.body());
        List<String> pal = palettes.computeIfAbsent(new PaletteKey(u.worldMsb(), u.worldLsb(), u.dimension()), k -> new ArrayList<>());
        if (u.startId() > pal.size()) throw new ProtocolException(Reason.BAD_FIELD, "palette gap at " + u.startId());
        for (int i = 0; i < u.keys().size(); i++) {
            int id = u.startId() + i;
            if (id < pal.size()) { if (!pal.get(id).equals(u.keys().get(i))) throw new ProtocolException(Reason.BAD_FIELD, "palette entry " + id + " changed"); }
            else { pal.add(u.keys().get(i)); counters.inc("client.palette.entries"); }
        }
        return List.of();
    }

    /** The palette received for a world/dimension (empty if none). */
    public synchronized List<String> palette(long worldMsb, long worldLsb, String dimension) {
        List<String> p = palettes.get(new PaletteKey(worldMsb, worldLsb, dimension));
        return p == null ? List.of() : List.copyOf(p);
    }

    private List<byte[]> onDrop(Frame f) {
        if (state != State.ACTIVE) { counters.inc("client.tile.unexpected"); return List.of(); }
        if ((negotiatedCaps & Awre.CAP_DROP_V1) == 0) throw new ProtocolException(Reason.UNKNOWN_TYPE, "DROP without negotiated capability");
        TileDrop d = TileDrop.decode(f.body(), lim);
        for (TileDrop.Key k : d.keys()) {
            if (store.remove(new TileKey(d.worldMsb(), d.worldLsb(), d.dimension(), k.tileX(), k.tileZ(), k.lod())) != null) counters.inc("client.tile.dropped");
        }
        return List.of();
    }

    private List<byte[]> onFragment(Frame f) {
        if (state != State.ACTIVE) { counters.inc("client.tile.unexpected"); return List.of(); }
        if ((negotiatedCaps & Awre.CAP_FRAGMENT_V1) == 0) throw new ProtocolException(Reason.UNKNOWN_TYPE, "FRAGMENT without negotiated capability");
        int maxTotal = negotiatedMaxDecoded + negotiatedMaxDecoded / 8 + 1024; // deflate worst case + TILE header
        Fragment fr = Fragment.decode(f.body(), maxTotal);
        byte[] whole = reassembler.accept(fr);
        return whole == null ? List.of() : processTile(fr.msgId(), whole);
    }

    private List<byte[]> onTile(Frame f) {
        if (state != State.ACTIVE) { counters.inc("client.tile.unexpected"); return List.of(); }
        return processTile(f.seq(), f.body());
    }

    /** ackId is the seq of the (first) frame that carried the tile; it is what the server matches the TILE_ACK against. */
    private List<byte[]> processTile(long ackId, byte[] tileBody) {
        TileMessage tm = TileMessage.decode(tileBody, lim);      // header errors -> ProtocolException -> strike (we cannot even name the tile)
        if (tm.maxDecodedLen() > negotiatedMaxDecoded) throw new ProtocolException(Reason.OUT_OF_RANGE, "tile exceeds negotiated decoded limit");
        TileKey key = new TileKey(tm.worldMsb(), tm.worldLsb(), tm.dimension(), tm.tileX(), tm.tileZ(), tm.lod());
        TileAck.Status status;
        try {
            byte[] decoded = tm.decodePayload();                 // bounded inflate + CRC
            if (!validator.accept(tm.tileX(), tm.tileZ(), tm.lod(), tm.revision(), decoded)) {
                status = TileAck.Status.INVALID; counters.inc("client.tile.content.mismatch");
            } else {
                Received old = store.get(key);
                if (old != null && tm.revision() < old.revision()) { status = TileAck.Status.STALE; counters.inc("client.tile.stale"); }
                else if (old != null && tm.revision() == old.revision()) { status = TileAck.Status.DUPLICATE; counters.inc("client.tile.duplicate"); }
                else { store.put(key, new Received(tm.revision(), tm.provenance(), decoded)); lastTile = key; status = TileAck.Status.OK; counters.inc("client.tile.accepted"); }
            }
        } catch (ProtocolException e) {
            strike(e);
            status = TileAck.Status.INVALID;
        }
        List<byte[]> r = new ArrayList<>(1);
        r.add(out(MessageType.TILE_ACK, new TileAck(ackId, status, tm.tileX(), tm.tileZ(), tm.lod(), tm.revision()).encode()));
        return r;
    }

    /** Replace the content check (default: G0 test tiles only). Applies to tiles received after the call. */
    public void setPayloadValidator(PayloadValidator v) { this.validator = java.util.Objects.requireNonNull(v); }

    // ---- diagnostics ----
    public synchronized State state() { return state; }
    public synchronized String reason() { return reason; }
    public synchronized int negotiatedVersion() { return negotiatedVersion; }
    public synchronized int negotiatedFrame() { return negotiatedFrame; }
    public synchronized int negotiatedCaps() { return negotiatedCaps; }
    public synchronized int partialTiles() { return reassembler.activeCount(); }
    public synchronized long sessionId() { return sessionId; }
    public synchronized int strikes() { return strikes; }
    public synchronized int tileCount() { return store.size(); }
    public synchronized Received tile(TileKey k) { return store.get(k); }
    public synchronized TileKey lastTile() { return lastTile; }
    /** Snapshot of the keys currently held (diagnostics and tests). */
    public synchronized java.util.Set<TileKey> tileKeys() { return new java.util.HashSet<>(store.keySet()); }
    public synchronized String lastServerError() { return lastServerError; }
}
