package dev.horizonfield.paper;

import dev.horizonfield.core.BoundedTaskQueue;
import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.WorkerPool;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Observes only chunks the server has ALREADY loaded (never loads or generates chunks).
 * Main thread: bounded queue of chunk coordinates, drained with a per-tick time budget, taking ChunkSnapshots.
 * Worker thread(s): analysis (pure Java) -> pyramid update -> tile log append.
 */
final class CaptureService {
    private record ChunkRef(UUID world, int cx, int cz) { }
    private record AnalysisJob(WorldState state, ChunkSnapshot snapshot) { }

    private final JavaPlugin plugin;
    private final HorizonConfig cfg;
    private final Metrics metrics;
    private final Path cacheDir;
    private final Map<UUID, WorldState> states = new ConcurrentHashMap<>();
    /** Worlds whose cache failed to open: do not retry (and re-log) on every chunk. */
    private final java.util.Set<UUID> failedWorlds = ConcurrentHashMap.newKeySet();
    private final BoundedTaskQueue<ChunkRef> captureQueue;
    private final BoundedTaskQueue<AnalysisJob> analysisQueue;
    private final WorkerPool<AnalysisJob> pool;
    private BukkitTask tickTask;
    private BukkitTask flushTask;

    CaptureService(JavaPlugin plugin, HorizonConfig cfg, Metrics metrics) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.metrics = metrics;
        this.cacheDir = plugin.getDataFolder().toPath().resolve("cache");
        this.captureQueue = new BoundedTaskQueue<>(cfg.captureQueueCapacity());
        this.analysisQueue = new BoundedTaskQueue<>(cfg.analysisQueueCapacity());
        this.pool = new WorkerPool<>("HorizonField-analysis", cfg.analysisThreads(), analysisQueue, this::analyze, metrics,
                t -> plugin.getLogger().warning("analysis error: " + t));
    }

    void start() {
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
        long flushTicks = cfg.flushIntervalSeconds() * 20L;
        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::flushAll, flushTicks, flushTicks);
    }

    /** Main thread. Cheap: only enqueues coordinates. */
    void request(World world, int cx, int cz) {
        if (!cfg.worldEnabled(world.getName())) return;
        double pri = nearestPlayerDistSq(world, cx * 16 + 8, cz * 16 + 8);
        BoundedTaskQueue.Offer o = captureQueue.offer(new ChunkRef(world.getUID(), cx, cz), pri, new ChunkRef(world.getUID(), cx, cz));
        metrics.inc(o == BoundedTaskQueue.Offer.REJECTED ? "capture.rejected" : "capture.requested");
    }

    private static double nearestPlayerDistSq(World w, double x, double z) {
        double best = 1e18;
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            double dx = l.getX() - x, dz = l.getZ() - z;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    /** Main thread, every tick, time-budgeted. */
    private void tick() {
        long budget = (long) (cfg.captureBudgetMillisPerTick() * 1_000_000L);
        captureQueue.drain(ref -> {
            try {
                snapshot(ref);
            } catch (RuntimeException e) {
                metrics.inc("capture.errors");
                if (metrics.get("capture.errors") <= 5) plugin.getLogger().warning("snapshot failed for " + ref + ": " + e);
            }
        }, budget, cfg.maxSnapshotsPerTick());
    }

    private void snapshot(ChunkRef ref) {
        World w = plugin.getServer().getWorld(ref.world());
        if (w == null || !w.isChunkLoaded(ref.cx(), ref.cz())) { metrics.inc("capture.skipped-unloaded"); return; }
        WorldState st = state(w);
        if (st == null) return;
        Chunk c = w.getChunkAt(ref.cx(), ref.cz()); // already loaded: no load/generation
        ChunkSnapshot snap = c.getChunkSnapshot(true, false, false);
        metrics.inc("capture.snapshots");
        analysisQueue.offer(ref, nearestPlayerDistSq(w, ref.cx() * 16 + 8, ref.cz() * 16 + 8), new AnalysisJob(st, snap));
    }

    private void analyze(AnalysisJob job) {
        long now = System.currentTimeMillis();
        ColumnTile tile = job.state().analyzer.analyze(new ChunkSnapshotView(job.snapshot(), job.state().minY, job.state().maxY), Confidence.OBSERVED, now);
        job.state().pyramid.put(tile);
        if (!job.state().deduper.shouldPersist(tile)) {
            metrics.inc("storage.skipped-unchanged");
            return;
        }
        try {
            job.state().log.append(tile);
            metrics.inc("storage.appended");
        } catch (IOException e) {
            metrics.inc("storage.errors");
            plugin.getLogger().warning("tile log append failed: " + e.getMessage());
        }
    }

    WorldState state(World w) {
        if (failedWorlds.contains(w.getUID())) return null;
        WorldState existing = states.get(w.getUID());
        if (existing != null) return existing;
        return states.computeIfAbsent(w.getUID(), id -> {
            try {
                WorldState s = WorldState.open(w, cacheDir, cfg.pyramidMaxLevel());
                plugin.getLogger().info("World '" + w.getName() + "': cache " + (s.loadReport.freshStart() ? "fresh (" + s.loadReport.reason() + ")"
                        : "loaded " + s.loadReport.tiles() + " tiles") + ", discarded " + s.loadReport.discardedBytes() + " bytes");
                return s;
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().severe("cannot open cache for " + w.getName() + " (disabled for this world until restart): " + e);
                failedWorlds.add(id);
                return null;
            }
        });
    }

    WorldState peek(World w) { return states.get(w.getUID()); }
    int captureQueueSize() { return captureQueue.size(); }
    int analysisQueueSize() { return analysisQueue.size(); }

    void flushAll() {
        for (WorldState s : states.values()) {
            try { s.log.flush(); } catch (IOException e) { metrics.inc("storage.errors"); }
        }
    }

    /** Safe shutdown: stop scheduling, stop workers, flush and close logs. */
    void shutdown() {
        if (tickTask != null) tickTask.cancel();
        if (flushTask != null) flushTask.cancel();
        captureQueue.close();
        int pending = analysisQueue.size();
        if (pending > 0) plugin.getLogger().warning(pending + " queued chunk analyses were dropped at shutdown (they will be re-observed on next load)");
        boolean clean = pool.shutdown(3000);
        if (!clean) plugin.getLogger().warning("analysis workers did not stop within 3s");
        for (WorldState s : states.values()) {
            try { s.close(); } catch (IOException e) { plugin.getLogger().warning("close failed: " + e.getMessage()); }
        }
        states.clear();
    }
}
