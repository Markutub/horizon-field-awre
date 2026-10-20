package dev.awre.stream;

import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.TileDeduper;
import dev.horizonfield.core.TileLog;
import dev.horizonfield.core.terrain.TerrainStore;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Disk persistence for captured terrain, built on the existing append-only, CRC-protected {@link TileLog} (the same format the
 * horizon-paper plugin uses, so a cache file is interchangeable). Opening replays the log into the store and the palette; a torn
 * tail is cut off, a different fingerprint starts fresh. Unchanged re-observations are not appended again. The palette passed in
 * must be brand new (the log restores ids into it). Compaction does not exist: the log grows with every real change.
 */
public final class TerrainPersistence implements AutoCloseable {
    private final TileLog log;
    private final TileDeduper deduper;
    private final Metrics metrics;
    private final TileLog.LoadReport report;

    private TerrainPersistence(TileLog log, TileDeduper deduper, Metrics metrics, TileLog.LoadReport report) {
        this.log = log; this.deduper = deduper; this.metrics = metrics; this.report = report;
    }

    public static TerrainPersistence open(Path file, String fingerprint, TerrainStore store, MaterialPalette palette, Metrics metrics) throws IOException {
        TileDeduper deduper = new TileDeduper();
        TileLog.Opened o = TileLog.open(file, fingerprint, palette, t -> { store.put(t); deduper.seed(t); });
        return new TerrainPersistence(o.log(), deduper, metrics, o.report());
    }

    public TileLog.LoadReport report() { return report; }

    /** Append the tile if its content changed since the last persisted one. Never throws: failures are counted. */
    void persist(ColumnTile t) {
        if (!deduper.shouldPersist(t)) { metrics.inc("persist.skipped-unchanged"); return; }
        try { log.append(t); metrics.inc("persist.appended"); }
        catch (IOException e) { metrics.inc("persist.errors"); }
    }

    public void flush() { try { log.flush(); } catch (IOException e) { metrics.inc("persist.errors"); } }

    @Override public void close() { try { log.close(); } catch (IOException e) { metrics.inc("persist.errors"); } }
}
