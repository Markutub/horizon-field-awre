package dev.awre.stream;

import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.TileDeduper;
import dev.horizonfield.core.TileLog;
import dev.horizonfield.core.terrain.TerrainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Disk persistence for captured terrain, built on the existing append-only, CRC-protected {@link TileLog} (the same format the
 * horizon-paper plugin uses, so a cache file is interchangeable). Opening replays the log into the store and the palette; a torn
 * tail is cut off, a different fingerprint starts fresh. Unchanged re-observations are not appended again. The palette passed in
 * must be brand new (the log restores ids into it). The log grows with every real change; {@link #compactIfWorthwhile} rewrites it
 * to the latest tile per chunk (callers: a periodic async task). Persisting waits while a compaction runs (one rewrite of the file).
 * The file is created LAZILY: if none exists yet, nothing touches the disk until the first tile is persisted, so worlds nobody
 * ever observes (nether, end, ...) leave no header-only files behind.
 */
public final class TerrainPersistence implements AutoCloseable {
    private final Object lock = new Object();
    private final Path file;
    private final String fingerprint;
    private final MaterialPalette palette;
    private final TileDeduper deduper;
    private final Metrics metrics;
    private final TileLog.LoadReport report;
    private TileLog log;          // null until the file exists (lazy creation); guarded by lock
    private boolean closed;       // guarded by lock

    private TerrainPersistence(Path file, String fingerprint, MaterialPalette palette, TileLog log, TileDeduper deduper, Metrics metrics, TileLog.LoadReport report) {
        this.file = file; this.fingerprint = fingerprint; this.palette = palette;
        this.log = log; this.deduper = deduper; this.metrics = metrics; this.report = report;
    }

    public static TerrainPersistence open(Path file, String fingerprint, TerrainStore store, MaterialPalette palette, Metrics metrics) throws IOException {
        TileDeduper deduper = new TileDeduper();
        if (!Files.exists(file)) {
            // nothing to replay and nothing worth writing yet: defer creating the file to the first persisted tile
            return new TerrainPersistence(file, fingerprint, palette, null, deduper, metrics, new TileLog.LoadReport(0, 0, true, "no cache file"));
        }
        TileLog.Opened o = TileLog.open(file, fingerprint, palette, t -> { store.put(t); deduper.seed(t); });
        return new TerrainPersistence(file, fingerprint, palette, o.log(), deduper, metrics, o.report());
    }

    public TileLog.LoadReport report() { return report; }

    /** True once the cache file exists on disk (it is created by the first persisted tile, or was there from the start). */
    public boolean fileCreated() { synchronized (lock) { return log != null; } }

    /** Append the tile if its content changed since the last persisted one. Never throws: failures are counted. */
    void persist(ColumnTile t) {
        if (!deduper.shouldPersist(t)) { metrics.inc("persist.skipped-unchanged"); return; }
        synchronized (lock) {
            if (closed) return;
            try {
                if (log == null) log = TileLog.open(file, fingerprint, palette, x -> { }).log();   // fresh file: the sink never fires
                log.append(t);
                metrics.inc("persist.appended");
            } catch (IOException | RuntimeException e) { metrics.inc("persist.errors"); }
        }
    }

    public void flush() {
        synchronized (lock) {
            if (log == null || closed) return;
            try { log.flush(); } catch (IOException e) { metrics.inc("persist.errors"); }
        }
    }

    /**
     * Compacts the cache file when at least {@code minGarbageRatio} of its tile records are superseded AND it is at least
     * {@code minBytes} long. Cheap to call often (two counters are read). Returns null when nothing was done or on failure
     * (failures are counted in {@code persist.errors}; the original file stays in use).
     */
    public TileLog.CompactReport compactIfWorthwhile(double minGarbageRatio, long minBytes) {
        synchronized (lock) {
            if (log == null || closed) return null;
            try {
                if (log.garbageRatio() < minGarbageRatio || log.sizeBytes() < minBytes) return null;
                TileLog.CompactReport r = log.compact();
                metrics.inc("persist.compactions");
                metrics.add("persist.compact-saved-bytes", r.bytesSaved());
                return r;
            } catch (IOException | RuntimeException e) { metrics.inc("persist.errors"); return null; }
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (log == null) return;
            try { log.close(); } catch (IOException e) { metrics.inc("persist.errors"); }
        }
    }
}
