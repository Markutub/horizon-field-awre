package dev.awre.stream;

import dev.awre.protocol.ClientSession;
import dev.awre.protocol.TileKey;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import dev.horizonfield.core.terrain.TerrainMesher;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client side: turns the tiles the {@link ClientSession} holds into meshes and keeps the most recently used ones (a mesh is rebuilt
 * when the tile's revision changed). Pure Java: a renderer calls {@link #get} for the tiles it wants to draw this frame.
 * Not thread-safe by design: call it from the render/client thread only.
 */
public final class TileMeshCache {
    private record Entry(long revision, TerrainMesher.Mesh mesh) { }

    private final ClientSession session;
    private final Map<TileKey, TileMeshCache.Entry> cache;
    private long hits, misses, failures;

    public TileMeshCache(ClientSession session, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.session = session;
        this.cache = new LinkedHashMap<>(64, 0.75f, true) {
            // TileMeshCache.Entry is spelled out on purpose: inside a LinkedHashMap subclass the bare name `Entry` can resolve to Map.Entry (JDK 25), a name clash
            @Override protected boolean removeEldestEntry(Map.Entry<TileKey, TileMeshCache.Entry> e) { return size() > capacity; }
        };
    }

    /** @return the mesh of the held tile, or null if the client does not hold it or its payload is not a terrain tile */
    public TerrainMesher.Mesh get(TileKey key) {
        ClientSession.Received rec = session.tile(key);
        if (rec == null) { cache.remove(key); return null; }
        Entry e = cache.get(key);
        if (e != null && e.revision == rec.revision()) { hits++; return e.mesh; }
        misses++;
        try {
            LodTile tile = LodTileCodec.decode(new LodKey(key.lod(), key.tileX(), key.tileZ()), rec.decoded());
            List<String> pal = session.palette(key.worldMsb(), key.worldLsb(), key.dimension());
            TerrainMesher.Mesh m = TerrainMesher.mesh(tile, id -> id >= 0 && id < pal.size() ? pal.get(id) : "minecraft:air", Math.max(4, 2 << key.lod()));
            cache.put(key, new Entry(rec.revision(), m));
            return m;
        } catch (IOException | RuntimeException ex) {
            failures++;
            cache.remove(key);
            return null;
        }
    }

    public long hits() { return hits; }
    public long misses() { return misses; }
    public long failures() { return failures; }
    public int size() { return cache.size(); }
}
