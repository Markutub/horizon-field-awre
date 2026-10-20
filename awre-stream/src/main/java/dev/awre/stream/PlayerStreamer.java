package dev.awre.stream;

import dev.awre.protocol.Encoding;
import dev.awre.protocol.Provenance;
import dev.awre.protocol.ServerSession;
import dev.awre.protocol.TileAck;
import dev.awre.protocol.TileDrop;
import dev.awre.protocol.TileMessage;
import dev.awre.protocol.PaletteUpdate;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.lod.LodPlan;
import dev.horizonfield.core.lod.LodPlanner;
import dev.horizonfield.core.stream.ByteBudget;
import dev.horizonfield.core.stream.InFlightSizer;
import dev.horizonfield.core.stream.PyramidTileSource;
import dev.horizonfield.core.stream.StreamScheduler;
import dev.horizonfield.core.terrain.LodTileCodec;
import dev.horizonfield.core.terrain.TerrainStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Drives one player's terrain stream: plan (when the viewer moved) -> schedule under a byte budget and in-flight window ->
 * build tiles from the {@link TerrainStore} -> hand them to the {@link ServerSession} -> forget what the scheduler says is
 * covered (DROP). Pure Java; the caller transports the returned frames.
 *
 * Threading: {@link #tick} must be called from ONE thread at a time. The session callbacks only touch the (internally
 * synchronised) scheduler and a concurrent map, and tick never holds a scheduler lock while calling the session, so
 * session-lock -> scheduler-lock is the only lock order that exists.
 */
public final class PlayerStreamer {
    public record Stats(int plannedLeaves, int held, int pending, long sent, long wireBytes, long dropped, int holes, int stale, int noData, long failures, long replans) { }

    private final ServerSession session;
    private final TerrainStore store;
    private final MaterialPalette palette;
    private final LodPlanner planner;
    private final StreamConfig cfg;
    private final long worldMsb, worldLsb;
    private final String dimension;
    private final StreamScheduler scheduler;
    private final PyramidTileSource source;
    private final ByteBudget budget;
    private final Map<LodKey, Integer> failures = new ConcurrentHashMap<>();
    private final AtomicLong sent = new AtomicLong(), wireBytes = new AtomicLong(), dropped = new AtomicLong(), failureEvents = new AtomicLong(), replans = new AtomicLong();

    private LodPlan plan;
    private double planX, planZ;
    private int lastHoles, lastStale, lastNoData;
    private int paletteSent;

    public PlayerStreamer(ServerSession session, TerrainStore store, MaterialPalette palette, LodPlanner planner, StreamConfig cfg,
                          long worldMsb, long worldLsb, String dimension, LongSupplier nanos) {
        this.session = session; this.store = store; this.palette = palette; this.planner = planner; this.cfg = cfg;
        this.worldMsb = worldMsb; this.worldLsb = worldLsb; this.dimension = dimension;
        this.scheduler = new StreamScheduler(planner.model());
        this.source = new PyramidTileSource(store, planner.model().maxLod(), 700);
        this.budget = new ByteBudget(cfg.burstBytes(), cfg.bytesPerSecond(), nanos);
        session.setTileListener(new ServerSession.TileListener() {
            @Override public void onAck(int tx, int tz, int lod, long rev, TileAck.Status st) {
                LodKey k = new LodKey(lod, tx, tz);
                switch (st) {
                    case OK, DUPLICATE -> { failures.remove(k); scheduler.onAck(k, rev, true); }
                    case STALE -> scheduler.onAck(k, Math.max(rev, store.version(k)), true);   // the client already holds something newer
                    case INVALID -> { fail(k); scheduler.onLost(k); }
                }
            }
            @Override public void onExpired(int tx, int tz, int lod, long rev) { LodKey k = new LodKey(lod, tx, tz); fail(k); scheduler.onLost(k); }
        });
    }

    private void fail(LodKey k) { failures.merge(k, 1, Integer::sum); failureEvents.incrementAndGet(); }

    /** One scheduling round. Returns the frames to transmit, in order (TILE/FRAGMENT first, then DROP). */
    public List<byte[]> tick(double x, double z, double headingRad) {
        List<byte[]> out = new ArrayList<>(), tileFrames = new ArrayList<>();
        if (session.state() != ServerSession.State.ACTIVE) return out;
        session.expireStale();                                   // otherwise a full window of lost tiles would block sending forever
        if (plan == null || Math.hypot(x - planX, z - planZ) >= cfg.replanBlocks()) {
            plan = planner.plan(x, z, cfg.radius(), plan); planX = x; planZ = z; replans.incrementAndGet();
        }
        int maxIn = session.maxInFlight();
        int window = Math.min(maxIn, InFlightSizer.size(cfg.bytesPerSecond(), cfg.rttSeconds(), source.estimatedBytes(new LodKey(0, 0, 0)), 1, maxIn));
        int free = window - session.inFlight();
        int room = cfg.maxHeldTiles() - scheduler.heldCount() - scheduler.pendingCount();
        int maxOrders = Math.max(0, Math.min(free, room));
        StreamScheduler.Decision d = scheduler.decide(plan, x, z, headingRad, budget.available(), maxOrders, source);
        lastHoles = d.holes(); lastStale = d.stale(); lastNoData = d.noData();

        for (StreamScheduler.Order o : d.send()) {
            LodKey k = o.key();
            if (failures.getOrDefault(k, 0) >= cfg.retryLimit()) continue;
            TerrainStore.Versioned v = store.versioned(k);
            if (v == null) continue;
            Provenance prov = v.tile().confidence() == Confidence.OBSERVED ? Provenance.AUTHORITATIVE : Provenance.PREDICTED;
            TileMessage tm = TileMessage.create(worldMsb, worldLsb, dimension, k.tx(), k.tz(), k.lod(), v.version(), prov, Encoding.DEFLATE, LodTileCodec.encode(v.tile()));
            long wire = tm.encode().length + 14L;
            if (!budget.tryTake(wire)) break;
            ServerSession.TileFrames tf = session.sendTile(tm);
            if (tf.result() == ServerSession.SendResult.SENT) {
                scheduler.onSent(k, v.version());
                tileFrames.addAll(tf.frames());
                sent.incrementAndGet(); wireBytes.addAndGet(wire);
                source.observe(k.lod(), (int) wire);
            } else {
                budget.refund(wire);
                if (tf.result() == ServerSession.SendResult.TOO_LARGE) failures.put(k, cfg.retryLimit());   // will never fit: stop trying
                else break;                                                                                  // rate limited / window full / not active: try again next tick
            }
        }

        // Palette entries go out BEFORE the tiles that may use them. The snapshot is taken after the tiles were built, and the
        // palette is append-only, so every id inside a tile of this round is covered.
        List<String> snap = palette.snapshot();
        if (snap.size() > paletteSent) {
            List<String> fresh = new ArrayList<>(snap.subList(paletteSent, snap.size()));
            for (int i = 0; i < fresh.size(); i++) if (!PaletteUpdate.validKey(fresh.get(i))) fresh.set(i, "invalid:key");   // keep ids aligned
            out.addAll(session.sendPalette(worldMsb, worldLsb, dimension, paletteSent, fresh));
            paletteSent = snap.size();
        }
        out.addAll(tileFrames);

        if (!d.evict().isEmpty()) {
            List<LodKey> drop = d.evict().size() > cfg.maxDropPerTick() ? d.evict().subList(0, cfg.maxDropPerTick()) : d.evict();
            List<TileDrop.Key> keys = new ArrayList<>(drop.size());
            for (LodKey k : drop) { scheduler.onEvicted(k); keys.add(new TileDrop.Key(k.tx(), k.tz(), k.lod())); }
            out.addAll(session.sendDrop(worldMsb, worldLsb, dimension, keys));   // no frames if the client did not negotiate DROP
            dropped.addAndGet(keys.size());
        }
        return out;
    }

    /** Forget retry blacklists (e.g. after the client reconnects). */
    public void resetFailures() { failures.clear(); }

    public LodPlan plan() { return plan; }

    public Stats stats() {
        return new Stats(plan == null ? 0 : plan.leaves().size(), scheduler.heldCount(), scheduler.pendingCount(), sent.get(), wireBytes.get(),
                dropped.get(), lastHoles, lastStale, lastNoData, failureEvents.get(), replans.get());
    }
}
