package dev.awre.stream;

import dev.horizonfield.core.BoundedTaskQueue;
import dev.horizonfield.core.ChunkView;
import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.TerrainAnalyzer;
import dev.horizonfield.core.WorkerPool;
import dev.horizonfield.core.terrain.TerrainStore;
import java.util.function.Consumer;

/**
 * Pure pipeline from observed chunks to the {@link TerrainStore}: a bounded, de-duplicating, distance-prioritised queue
 * (the same one the capture plugin uses) drained by worker threads that run {@link TerrainAnalyzer} and put the tile.
 * The caller (Bukkit side) only has to produce {@link ChunkView}s that are safe to read off the main thread (e.g. over a
 * ChunkSnapshot) and call {@link #submit}. Chunks are labelled OBSERVED: only submit what was really read from the world.
 */
public final class ChunkIngest implements AutoCloseable {
    private final BoundedTaskQueue<ChunkView> queue;
    private final WorkerPool<ChunkView> pool;
    private final TerrainAnalyzer analyzer;
    private final Metrics metrics;

    private final TerrainPersistence persistence;

    public ChunkIngest(TerrainStore store, MaterialPalette palette, int queueCapacity, int threads, Metrics metrics, Consumer<Throwable> errorSink) {
        this(store, palette, queueCapacity, threads, metrics, errorSink, null);
    }

    /** @param persistence optional: every stored tile is also appended to it (deduplicated); closed together with this ingest */
    public ChunkIngest(TerrainStore store, MaterialPalette palette, int queueCapacity, int threads, Metrics metrics, Consumer<Throwable> errorSink,
                       TerrainPersistence persistence) {
        this.metrics = metrics;
        this.persistence = persistence;
        this.queue = new BoundedTaskQueue<>(queueCapacity);
        this.analyzer = new TerrainAnalyzer(palette);
        this.pool = new WorkerPool<>("AWRE-ingest", threads, queue, view -> {
            ColumnTile t = analyzer.analyze(view, Confidence.OBSERVED, System.currentTimeMillis());
            store.put(t);
            if (persistence != null) persistence.persist(t);
            metrics.inc("ingest.stored");
        }, metrics, errorSink);
    }

    /** @param priority lower = sooner (e.g. squared distance to the nearest player) @return false if the queue rejected it */
    public boolean submit(ChunkView view, double priority) {
        long key = ((long) view.chunkX() << 32) | (view.chunkZ() & 0xFFFFFFFFL);
        BoundedTaskQueue.Offer o = queue.offer(key, priority, view);
        metrics.inc(o == BoundedTaskQueue.Offer.REJECTED ? "ingest.rejected" : "ingest.submitted");
        return o != BoundedTaskQueue.Offer.REJECTED;
    }

    public int queued() { return queue.size(); }

    @Override public void close() { queue.close(); pool.shutdown(3000); if (persistence != null) { persistence.flush(); persistence.close(); } }
}
