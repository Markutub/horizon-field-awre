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

    /** ~10 Hz: refresh which tiles are wanted (the view meshes new/changed tiles in the background). */
    public void tick(double camX, double camZ, double vanillaRadius, double maxDistance, Predicate<TileKey> worldFilter) {
        wanted = view.update(camX, camZ, vanillaRadius, maxDistance, worldFilter);
        candidates = view.candidateOrder();
        dirty = true;
    }

    /** Every rendered frame: bounded uploads, then the draw list (nearest first) relative to the camera. */
    public List<Draw<H>> frame(double camX, double camY, double camZ) {
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

    public String diagnosticsLine() {
        Diagnostics d = diagnostics();
        return "received=" + d.tilesReceived() + " mesh_built=" + d.meshesBuilt() + " mesh_failed=" + d.meshFailures()
                + " gpu_uploaded=" + d.uploads() + " upload_failed=" + d.uploadFailures() + " gpu_freed=" + d.freed()
                + " gpu_resident=" + d.resident() + " gpu_bytes=" + d.residentBytes()
                + " planned_draws=" + d.drawsLastFrame() + " planned_tris=" + d.trianglesLastFrame()
                + " submitted_draws=" + d.drawsSubmittedTotal() + " submitted_frames=" + d.framesWithDraws();
    }

    /** Frees all GPU meshes and forgets the plan (disconnect, dimension change, shutdown). The view keeps running. Idempotent. */
    public void release() {
        residency.releaseAll();
        wanted = List.of(); candidates = List.of(); drawKeys = List.of();
        drawsLastFrame = 0; trianglesLastFrame = 0;
        dirty = true;
    }

    @Override public void close() { release(); view.close(); }
}
