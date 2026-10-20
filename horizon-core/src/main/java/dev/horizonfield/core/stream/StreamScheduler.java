package dev.horizonfield.core.stream;

import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.lod.LodModel;
import dev.horizonfield.core.lod.LodPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player tile scheduler (pure Java, no I/O). Decides WHAT to send under a byte budget and WHAT the client may drop.
 *
 * Send order is greedy by benefit/cost, the standard 1/2-approximation for a knapsack of independent items:
 *   benefit = (currentErrorPx - newErrorPx) * screenArea * viewWeight,   cost = estimated bytes.
 * An uncovered area counts as a hole with error holePx. Tiles whose area is already covered by finer held tiles only get
 * a small "consolidation" priority so memory can be freed once bandwidth is idle.
 *
 * Eviction is keep-until-replaced: a held tile is dropped only when its whole footprint is already covered by acknowledged,
 * up-to-date planned leaves, or when it lies entirely outside the plan (docs/TECHNIQUE_MAP section 4b: evicting before the
 * replacement exists was the cause of the starved-elytra loss).
 */
public final class StreamScheduler {
    public interface TileSource {
        int estimatedBytes(LodKey k);
        long revision(LodKey k);
    }

    public record Order(LodKey key, double priority, int bytes) { }
    public record Decision(List<Order> send, List<LodKey> evict, int holes, int stale, int pending, int noData) { }

    private final LodModel m;
    private final double holePx, stalePx, viewFloor, consolidation;
    private final Map<LodKey, Long> held = new HashMap<>();     // acknowledged tiles -> revision
    private final Map<LodKey, Long> pending = new HashMap<>();  // sent, not yet acknowledged

    public StreamScheduler(LodModel model) { this(model, model.viewportHeightPx() / 4.0, 4.0, 0.05, 1e-3); }

    public StreamScheduler(LodModel model, double holePx, double stalePx, double viewFloor, double consolidation) {
        this.m = model; this.holePx = holePx; this.stalePx = stalePx; this.viewFloor = viewFloor; this.consolidation = consolidation;
    }

    public synchronized void onSent(LodKey k, long rev) { pending.put(k, rev); }
    public synchronized void onAck(LodKey k, long rev, boolean ok) {
        Long p = pending.get(k);
        if (p != null && p == rev) pending.remove(k);
        if (ok) held.merge(k, rev, Math::max);
    }
    /** An in-flight tile timed out or the client reported INVALID: forget it so it can be scheduled again. */
    public synchronized void onLost(LodKey k) { pending.remove(k); }
    public synchronized void onEvicted(LodKey k) { held.remove(k); }
    public synchronized boolean holds(LodKey k) { return held.containsKey(k); }
    public synchronized int heldCount() { return held.size(); }
    public synchronized int pendingCount() { return pending.size(); }
    public synchronized Map<LodKey, Long> heldSnapshot() { return new HashMap<>(held); }

    public synchronized Decision decide(LodPlan plan, double px, double pz, double headingRad, long budgetBytes, int maxOrders, TileSource src) {
        Set<LodKey> ready = new HashSet<>();
        List<Order> candidates = new ArrayList<>();
        int holes = 0, stale = 0, noData = 0;
        for (LodKey k : plan.leaves()) {
            long rev = src.revision(k);
            if (rev <= 0) { noData++; continue; }                                  // nothing to send (area never observed)
            Long h = held.get(k);
            if (h != null && h >= rev) { ready.add(k); continue; }
            Long p = pending.get(k);
            if (p != null && p >= rev) continue;                                  // already on its way
            double dist = distance(k, px, pz);
            double size = m.tileSize(k.lod());
            double area = Math.min(4.0, (size / Math.max(dist, 1.0)) * (size / Math.max(dist, 1.0)));
            double w = viewWeight(k, px, pz, headingRad, dist, size);
            double newErr = m.projectedPx(k.lod(), dist);
            double benefit;
            if (h != null) { stale++; benefit = stalePx * area * w; }             // same tile, outdated content
            else {
                CoverState cs = coverage(k, px, pz, dist);
                if (cs.kind == Kind.NONE) { holes++; benefit = Math.max(0, holePx - newErr) * area * w; }
                else if (cs.kind == Kind.COARSER) benefit = Math.max(0, cs.errPx - newErr) * area * w;
                else benefit = consolidation * cs.heldDescendants;                // finer tiles already cover it: only memory to gain
            }
            int bytes = Math.max(1, src.estimatedBytes(k));
            if (benefit > 0) candidates.add(new Order(k, benefit / bytes, bytes));
        }
        candidates.sort(Comparator.comparingDouble(Order::priority).reversed().thenComparing(o -> o.key().lod()).thenComparing(o -> o.key().tx()).thenComparing(o -> o.key().tz()));
        List<Order> send = new ArrayList<>();
        long spent = 0;
        for (Order o : candidates) {
            if (send.size() >= maxOrders) break;
            if (spent + o.bytes() > budgetBytes) continue;                          // knapsack-greedy: skip what does not fit, try smaller ones
            send.add(o); spent += o.bytes();
        }
        List<LodKey> evict = new ArrayList<>();
        for (LodKey k : held.keySet()) {
            if (plan.isLeaf(k)) continue;
            if (outsidePlan(k, plan) || covered(k, ready)) evict.add(k);
        }
        evict.sort(Comparator.comparingInt(LodKey::lod).thenComparingInt(LodKey::tx).thenComparingInt(LodKey::tz));
        return new Decision(send, evict, holes, stale, pending.size(), noData);
    }

    // ---- geometry ----

    private double distance(LodKey k, double px, double pz) {
        double s = m.tileSize(k.lod()), minX = k.tx() * s, minZ = k.tz() * s;
        double dx = Math.max(Math.max(minX - px, 0), px - (minX + s)), dz = Math.max(Math.max(minZ - pz, 0), pz - (minZ + s));
        return Math.sqrt(dx * dx + dz * dz + m.eyeHeight() * m.eyeHeight());
    }

    private double viewWeight(LodKey k, double px, double pz, double heading, double dist, double size) {
        if (dist <= size) return 1.0;                                              // we are on or next to it
        double s = size, cx = (k.tx() + 0.5) * s - px, cz = (k.tz() + 0.5) * s - pz;
        double ang = Math.atan2(cz, cx) - heading;
        return Math.max(viewFloor, 0.5 * (1.0 + Math.cos(ang)));
    }

    private enum Kind { NONE, COARSER, FINER }
    private record CoverState(Kind kind, double errPx, int heldDescendants) { }

    private CoverState coverage(LodKey k, double px, double pz, double dist) {
        LodKey a = k.parent();
        for (int l = k.lod() + 1; l <= m.maxLod(); l++, a = a.parent()) if (held.containsKey(a)) return new CoverState(Kind.COARSER, m.projectedPx(l, dist), 0);
        int count = descendantsHeld(k);
        if (count > 0 && fullyCoveredByHeld(k)) return new CoverState(Kind.FINER, 0, count);
        return new CoverState(Kind.NONE, 0, 0);
    }

    private int descendantsHeld(LodKey k) {
        int c = 0;
        for (LodKey h : held.keySet()) if (h.lod() < k.lod() && k.contains(h)) c++;
        return c;
    }

    private boolean fullyCoveredByHeld(LodKey k) {
        if (held.containsKey(k)) return true;
        if (k.lod() == 0) return false;
        for (LodKey c : k.children()) if (!fullyCoveredByHeld(c)) return false;
        return true;
    }

    /** Footprint of k is entirely covered by `ready` leaves (an ancestor-or-self in ready, or all four children covered). */
    private boolean covered(LodKey k, Set<LodKey> ready) {
        LodKey a = k;
        for (int l = k.lod(); l <= m.maxLod(); l++, a = a.parent()) if (ready.contains(a)) return true;
        return k.lod() > 0 && allChildrenCovered(k, ready);
    }

    private boolean allChildrenCovered(LodKey k, Set<LodKey> ready) {
        if (ready.contains(k)) return true;
        if (k.lod() == 0) return false;
        for (LodKey c : k.children()) if (!allChildrenCovered(c, ready)) return false;
        return true;
    }

    private boolean outsidePlan(LodKey k, LodPlan plan) {
        if (plan.isSplit(k)) return false;                                         // a planned leaf lies inside it
        LodKey a = k;
        for (int l = k.lod(); l <= m.maxLod(); l++, a = a.parent()) if (plan.isLeaf(a)) return false;
        return true;
    }
}
