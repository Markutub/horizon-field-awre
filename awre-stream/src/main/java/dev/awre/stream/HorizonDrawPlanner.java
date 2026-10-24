package dev.awre.stream;

import dev.awre.protocol.TileKey;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Everything between {@link ClientHorizonView} and the actual GPU calls, in pure Java: owns the residency, decides every frame what
 * to upload and what to draw, and computes each draw's camera-relative offset. The Minecraft side (awre-fabric) only turns
 * {@link Draw} entries into render-pass calls and reports back with {@link #recordSubmitted}.
 * Threading: {@link #tick} and {@link #frame} and {@link #release} are all called on the client/render thread (the only
 * thread-crossing work is mesh building inside the view, which never touches GPU objects).
 */
public final class HorizonDrawPlanner<H> implements AutoCloseable {
    /** One draw call: the resident mesh plus the offset to add to its tile-local vertices. */
    public record Draw<H>(TileKey key, long revision, H handle, int indexCount, float offX, float offY, float offZ) { }

    /** Counters that keep the stages apart: received -> mesh built -> uploaded -> draw submitted. */
    public record Diagnostics(int tilesReceived, long meshesBuilt, long meshFailures, long uploads, long uploadFailures, long freed,
                              int resident, long residentBytes, int drawsLastFrame, long trianglesLastFrame, long drawsSubmittedTotal,
                              long framesWithDraws) { }

    private final ClientHorizonView view;
    private final MeshResidency<H> residency;
    private List<ClientHorizonView.Drawable> wanted = List.of();
    private List<TileKey> candidates = List.of();
    private List<TileKey> drawKeys = List.of();
    private boolean dirty = true;
    private int drawsLastFrame;
    private long trianglesLastFrame, drawsSubmitted, framesWithDraws;

    public HorizonDrawPlanner(ClientHorizonView view, MeshResidency<H> residency) { this.view = view; this.residency = residency; }

    public MeshResidency<H> residency() { return residency; }

    private RevealAnimator reveal;

    /** Optional reveal effect state. {@link #release()} (dimension change, disconnect, shutdown) resets it so the next session reveals again. */
    public void setReveal(RevealAnimator r) { this.reveal = r; }
    public RevealAnimator reveal() { return reveal; }

    /** ~10 Hz: refresh which tiles are wanted (the view meshes new/changed tiles in the background). */
    public void tick(double camX, double camZ, double vanillaRadius, double maxDistance, Predicate<TileKey> worldFilter) {
        wanted = view.update(camX, camZ, vanillaRadius, maxDistance, worldFilter);
        candidates = view.candidateOrder();
        dirty = true;
    }

    /** Every rendered frame: bounded uploads, then the draw list (nearest first) relative to the camera. */
    public List<Draw<H>> frame(double camX, double camY, double camZ) {
        residency.endFrame();                                  // delayed frees from earlier frames come due here
        if (residency.sync(wanted, candidates)) dirty = true;
        if (dirty) { drawKeys = view.select(residency::isResident); dirty = false; }
        List<Draw<H>> out = new ArrayList<>(drawKeys.size());
        long tris = 0;
        for (TileKey k : drawKeys) {
            MeshResidency.Resident<H> r = residency.get(k);
            if (r == null || r.handle() == null || r.indexCount() == 0) continue;
            float[] o = CameraRelative.offset(r.originX(), r.originZ(), camX, camY, camZ);
            out.add(new Draw<>(k, r.revision(), r.handle(), r.indexCount(), o[0], o[1], o[2]));
            tris += r.indexCount() / 3;
        }
        drawsLastFrame = out.size();
        trianglesLastFrame = tris;
        return out;
    }

    /** The renderer calls this after the draw calls of one frame were actually issued (not merely planned). */
    public void recordSubmitted(int draws) {
        if (draws <= 0) return;
        drawsSubmitted += draws;
        framesWithDraws++;
    }

    public Diagnostics diagnostics() {
        ClientHorizonView.Stats s = view.stats();
        MeshResidency.Counters c = residency.counters();
        return new Diagnostics(s.held(), s.built(), s.failures(), c.uploads(), c.uploadFailures(), c.frees(), residency.size(), residency.bytes(),
                drawsLastFrame, trianglesLastFrame, drawsSubmitted, framesWithDraws);
    }

    /** Meshes the view has built that hold no GPU slot right now (waiting for the bounded upload budget, over a residency cap, or not wanted). Sorted for stable logs. */
    public List<TileKey> builtNotResident() {
        java.util.Set<TileKey> b = view.meshedKeys();
        b.removeAll(residency.keys());
        List<TileKey> out = new ArrayList<>(b);
        out.sort(java.util.Comparator.comparing((TileKey k) -> k.lod()).thenComparing(k -> k.tileX()).thenComparing(k -> k.tileZ()));
        return out;
    }

    public String diagnosticsLine() {
        Diagnostics d = diagnostics();
        return "received=" + d.tilesReceived() + " mesh_built=" + d.meshesBuilt() + " mesh_failed=" + d.meshFailures()
                + " gpu_uploaded=" + d.uploads() + " upload_failed=" + d.uploadFailures() + " gpu_freed=" + d.freed()
                + " gpu_resident=" + d.resident() + " gpu_bytes=" + d.residentBytes()
                + " planned_draws=" + d.drawsLastFrame() + " planned_tris=" + d.trianglesLastFrame()
                + " submitted_draws=" + d.drawsSubmittedTotal() + " submitted_frames=" + d.framesWithDraws()
                + notResidentSuffix();
    }

    /**
     * Splits {@link #builtNotResident()} into {@code [notWanted, waiting]}: meshes the CURRENT plan does not want (inside the vanilla
     * square, hidden by finer tiles, too far; the view keeps them as a CPU cache, or they were built just before the camera or the
     * render distance changed) versus meshes the plan wants that wait for an upload slot (bounded upload budget or residency cap).
     * The first kind is normal, the second drains within a few frames; a persistent {@code waiting} count would be a defect.
     */
    public int[] builtNotResidentSplit() {
        java.util.Set<TileKey> b = view.meshedKeys();
        b.removeAll(residency.keys());
        int waiting = 0;
        for (ClientHorizonView.Drawable d : wanted) if (b.contains(d.key())) waiting++;
        return new int[]{b.size() - waiting, waiting};
    }

    /** Appended AFTER the legacy counters so existing parsers keep working: " built_not_wanted=K built_waiting=M built_not_resident=N [lod/tx/tz ...]" (first 4). */
    private String notResidentSuffix() {
        List<TileKey> l = builtNotResident();
        int[] split = builtNotResidentSplit();
        StringBuilder sb = new StringBuilder(" built_not_wanted=").append(split[0]).append(" built_waiting=").append(split[1])
                .append(" built_not_resident=").append(l.size());
        if (!l.isEmpty()) {
            sb.append(" sample=[");
            for (int i = 0; i < Math.min(4, l.size()); i++) sb.append(i == 0 ? "" : " ").append(l.get(i).lod()).append('/').append(l.get(i).tileX()).append('/').append(l.get(i).tileZ());
            sb.append(']');
        }
        return sb.toString();
    }

    /** Frees all GPU meshes and forgets the plan (disconnect, dimension change, shutdown). The view keeps running. Idempotent. */
    public void release() {
        if (reveal != null) reveal.reset();
        residency.releaseAll();
        wanted = List.of(); candidates = List.of(); drawKeys = List.of();
        drawsLastFrame = 0; trianglesLastFrame = 0;
        dirty = true;
    }

    @Override public void close() { release(); residency.flush(); view.close(); }
}
