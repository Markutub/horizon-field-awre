package dev.awre.paper;

import dev.awre.stream.ChunkIngest;
import dev.awre.stream.TerrainPersistence;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.terrain.TerrainStore;

/** Terrain of one world (or the shared synthetic one): the store, its palette and, in captured mode, the ingest pipeline. */
final class AwreWorldTerrain {
    final TerrainStore store;
    final MaterialPalette palette;
    final int minY, maxY;
    volatile ChunkIngest ingest;                 // null for synthetic terrain, and until the persisted cache has been replayed
    volatile TerrainPersistence persistence;     // null if persistence is off or failed to open
    volatile boolean ready;

    AwreWorldTerrain(TerrainStore store, MaterialPalette palette, int minY, int maxY) {
        this.store = store; this.palette = palette; this.minY = minY; this.maxY = maxY;
    }

    void flush() { TerrainPersistence p = persistence; if (p != null) p.flush(); }

    void close() { ChunkIngest in = ingest; if (in != null) in.close(); else { TerrainPersistence p = persistence; if (p != null) p.close(); } }
}
