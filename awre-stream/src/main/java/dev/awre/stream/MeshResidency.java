package dev.awre.stream;

import dev.awre.protocol.TileKey;
import dev.horizonfield.core.terrain.TerrainMesher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which tile meshes currently live on the GPU, and the rules for changing that. Pure Java: the actual GPU calls are behind
 * {@link Backend} (Minecraft implements it in awre-fabric). Single-threaded: call from the render thread only.
 * <ul>
 * <li>A new revision is uploaded FIRST; the old handle is freed only after the new one exists. If the upload fails the old mesh
 *     stays resident and keeps drawing, and that revision is not retried every frame.</li>
 * <li>Uploads per call are bounded by count and bytes; residency is bounded by count and bytes (the farthest tiles are evicted first).</li>
 * <li>Tiles that are not candidates any more (DROP, out of range, other dimension) are freed on the next {@link #sync}.</li>
 * <li>{@link #releaseAll} frees everything (disconnect, dimension change, shutdown) and is idempotent.</li>
 * <li>Optional deferred free ({@code freeDelayFrames > 0}): a handle that was drawn recently is closed only after that many
 *     {@link #endFrame} calls, because explicit-API backends (Vulkan) may still have the previous frames' draws in flight.
 *     {@link #flush} closes everything pending at once (shutdown). Default 0 = free immediately (previous behaviour).</li>
 * </ul>
 */
public final class MeshResidency<H> {
    /** The GPU side. {@code upload} may throw; it must not leave a half-created resource behind. */
    public interface Backend<H> {
        H upload(TileKey key, TerrainMesher.Mesh mesh);
        void free(H handle);
    }

    /** What is on the GPU for one tile. {@code handle} is null for a tile whose mesh has no triangles (nothing to draw, but it counts as covered). */
    public record Resident<H>(TileKey key, long revision, H handle, int indexCount, int bytes, double originX, double originZ) { }

    public record Limits(int maxResident, long maxResidentBytes, int maxUploadsPerSync, long maxUploadBytesPerSync) {
        public Limits {
            if (maxResident < 1 || maxResidentBytes < 1 || maxUploadsPerSync < 1 || maxUploadBytesPerSync < 1) throw new IllegalArgumentException("limits must be positive");
        }
        public static Limits defaults() { return new Limits(4096, 192L << 20, 8, 2L << 20); }
    }

    public record Counters(long uploads, long uploadBytes, long uploadFailures, long frees, long freeFailures, long capEvictions, long capSkipped, long revisionReplacements) { }

    private final Backend<H> backend;
    private final Limits limits;
    private final Map<TileKey, Resident<H>> slots = new HashMap<>();
    private final Map<TileKey, Long> failedRevision = new HashMap<>();
    private record Pending<H>(H handle, int framesLeft) { }
    private final ArrayDeque<Pending<H>> pending = new ArrayDeque<>();
    private final int freeDelayFrames;
    private long residentBytes;
    private long uploads, uploadBytes, uploadFailures, frees, freeFailures, capEvictions, capSkipped, replacements;

    public MeshResidency(Backend<H> backend, Limits limits) { this(backend, limits, 0); }

    /** @param freeDelayFrames how many {@link #endFrame} calls a freed handle waits before the backend closes it (0 = immediately) */
    public MeshResidency(Backend<H> backend, Limits limits, int freeDelayFrames) {
        if (freeDelayFrames < 0) throw new IllegalArgumentException("freeDelayFrames");
        this.backend = backend; this.limits = limits; this.freeDelayFrames = freeDelayFrames;
    }

    /** Handles waiting for their delayed close. */
    public int pendingFrees() { return pending.size(); }

    /** Call once per rendered frame (render thread): closes handles whose delay has elapsed. */
    public void endFrame() {
        int n = pending.size();
        for (int i = 0; i < n; i++) {
            Pending<H> p = pending.poll();
            if (p.framesLeft() <= 1) closeNow(p.handle()); else pending.add(new Pending<>(p.handle(), p.framesLeft() - 1));
        }
    }

    /** Closes every pending handle now (shutdown / disconnect: nothing will be drawn again). Safe to call repeatedly. */
    public void flush() {
        Pending<H> p;
        while ((p = pending.poll()) != null) closeNow(p.handle());
    }

    public boolean isResident(TileKey k) { return slots.containsKey(k); }
    public Resident<H> get(TileKey k) { return slots.get(k); }
    public int size() { return slots.size(); }
    public long bytes() { return residentBytes; }
    public Set<TileKey> keys() { return new HashSet<>(slots.keySet()); }
    public Counters counters() { return new Counters(uploads, uploadBytes, uploadFailures, frees, freeFailures, capEvictions, capSkipped, replacements); }

    /**
     * @param wanted     drawables from {@link ClientHorizonView#update}, nearest first (CPU meshes that exist)
     * @param candidates nearest-first keys that may stay resident ({@link ClientHorizonView#candidateOrder()})
     * @return true if anything was uploaded or freed (the draw selection must be recomputed)
     */
    public boolean sync(List<ClientHorizonView.Drawable> wanted, List<TileKey> candidates) {
        boolean changed = false;
        Map<TileKey, Integer> rank = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) rank.put(candidates.get(i), i);

        for (TileKey k : new ArrayList<>(slots.keySet())) if (!rank.containsKey(k)) { free(slots.remove(k)); changed = true; }
        failedRevision.keySet().retainAll(rank.keySet());

        int doneUploads = 0; long doneBytes = 0;
        for (ClientHorizonView.Drawable d : wanted) {
            if (doneUploads >= limits.maxUploadsPerSync() || doneBytes >= limits.maxUploadBytesPerSync()) break;
            Resident<H> have = slots.get(d.key());
            if (have != null && have.revision() == d.revision()) continue;
            Long bad = failedRevision.get(d.key());
            if (bad != null && bad == d.revision()) continue;
            Integer myRank = rank.get(d.key());
            if (myRank == null) continue;
            TerrainMesher.Mesh m = d.mesh();
            int bytes = VertexPacker.vertexBytes(m) + VertexPacker.indexBytes(m);
            if (!makeRoom(d.key(), bytes, myRank, rank)) { capSkipped++; continue; }
            H handle = null;
            if (m.triangleCount() > 0) {
                try {
                    handle = backend.upload(d.key(), m);
                } catch (RuntimeException e) {
                    uploadFailures++;
                    failedRevision.put(d.key(), d.revision());       // the old resident mesh (if any) stays and keeps drawing
                    continue;
                }
            }
            Resident<H> fresh = new Resident<>(d.key(), d.revision(), handle, m.indices.length, bytes, m.originX, m.originZ);
            Resident<H> old = slots.put(d.key(), fresh);
            residentBytes += bytes;
            uploads++; uploadBytes += bytes; doneUploads++; doneBytes += bytes; changed = true;
            if (old != null) { replacements++; free(old); }
        }
        return changed;
    }

    /** Frees farthest residents (strictly farther than {@code myRank}) until a new mesh of {@code bytes} fits. */
    private boolean makeRoom(TileKey key, long bytes, int myRank, Map<TileKey, Integer> rank) {
        Resident<H> replacing = slots.get(key);
        while (true) {
            int count = slots.size() + (replacing == null ? 1 : 0);
            long total = residentBytes + bytes;   // the old revision is freed after the upload, so both exist briefly
            if (count <= limits.maxResident() && total <= limits.maxResidentBytes()) return true;
            TileKey victim = null; int victimRank = myRank;
            for (TileKey k : slots.keySet()) {
                if (k.equals(key)) continue;
                int r = rank.getOrDefault(k, Integer.MAX_VALUE);
                if (r > victimRank) { victimRank = r; victim = k; }
            }
            if (victim == null) return false;
            free(slots.remove(victim));
            capEvictions++;
        }
    }

    private void free(Resident<H> r) {
        residentBytes -= r.bytes();
        if (r.handle() == null) return;
        if (freeDelayFrames > 0) pending.add(new Pending<>(r.handle(), freeDelayFrames));
        else closeNow(r.handle());
    }

    private void closeNow(H handle) {
        try { backend.free(handle); frees++; } catch (RuntimeException e) { freeFailures++; }
    }

    /** Frees every GPU mesh. Safe to call repeatedly. */
    public void releaseAll() {
        for (Resident<H> r : new ArrayList<>(slots.values())) free(r);
        slots.clear();
        failedRevision.clear();
        residentBytes = 0;
    }
}
