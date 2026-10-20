package dev.horizonfield.core.stream;

import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.TerrainStore;

/** Connects the scheduler to a {@link TerrainStore}: revision = pyramid version, size = per-level moving average of real wire sizes. */
public final class PyramidTileSource implements StreamScheduler.TileSource {
    private final TerrainStore store;
    private final double[] avg;

    public PyramidTileSource(TerrainStore store, int maxLod, int initialGuessBytes) {
        this.store = store;
        this.avg = new double[maxLod + 1];
        java.util.Arrays.fill(avg, initialGuessBytes);
    }

    @Override public synchronized int estimatedBytes(LodKey k) { return (int) Math.max(1, avg[Math.min(k.lod(), avg.length - 1)]); }
    @Override public long revision(LodKey k) { return store.version(k); }

    /** Fold an observed wire size into the level's estimate (EMA, alpha 0.2). */
    public synchronized void observe(int lod, int wireBytes) {
        int l = Math.min(lod, avg.length - 1);
        avg[l] = 0.8 * avg[l] + 0.2 * wireBytes;
    }
}
