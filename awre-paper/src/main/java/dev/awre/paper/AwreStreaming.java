package dev.awre.paper;

import dev.awre.protocol.Awre;
import dev.awre.protocol.ServerSession;
import dev.awre.stream.ChunkIngest;
import dev.awre.stream.PlayerStreamer;
import dev.awre.stream.StreamConfig;
import dev.awre.stream.SyntheticTerrain;
import dev.awre.stream.TerrainPersistence;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.TileLog;
import dev.horizonfield.core.lod.LodModel;
import dev.horizonfield.core.lod.LodPlanner;
import dev.horizonfield.core.terrain.TerrainStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs {@link PlayerStreamer}s for AWRE players. SOURCE-ONLY (never compiled against Paper yet).
 * Threading: a main-thread timer (every 2 ticks) reads each player's position and starts ONE async job per player
 * (planning takes tens of ms); the job returns frames, which are sent from the main thread before the next job may start.
 * Terrain: SYNTHETIC (one shared made-up terrain, PREDICTED) or CAPTURED (one store per world from loaded chunks, OBSERVED).
 */
final class AwreStreaming {
    private static final long SYNTHETIC_SEED = 1234;
    private static final UUID SYNTHETIC_KEY = new UUID(0, 0);

    private static final class Entry {
        final PlayerStreamer streamer; final UUID world; final AtomicBoolean busy = new AtomicBoolean();
        volatile PlayerStreamer.Stats lastLogged;
        Entry(PlayerStreamer s, UUID w) { streamer = s; world = w; }
    }

    private final JavaPlugin plugin;
    private final AwreChannel channel;
    private final LodModel model = LodModel.defaults();
    private final Metrics metrics = new Metrics();
    private final Map<UUID, AwreWorldTerrain> terrains = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private volatile AwreConfig cfg;
    private AwreCapture capture;
    private BukkitTask task, flushTask;
    private int rounds;

    AwreStreaming(JavaPlugin plugin, AwreChannel channel) { this.plugin = plugin; this.channel = channel; }

    void start(AwreConfig c) {
        this.cfg = c;
        if (c.streamCaptured()) {
            capture = new AwreCapture(plugin, this);
            capture.start();
            // load every world's terrain (and replay its disk cache) now, not when the first chunk event or player happens to touch it
            plugin.getServer().getScheduler().runTask(plugin, () -> { for (World w : plugin.getServer().getWorlds()) terrainFor(w); });
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 2L);
        flushTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (Map.Entry<UUID, AwreWorldTerrain> e : terrains.entrySet()) {
                e.getValue().flush();
                TileLog.CompactReport r = e.getValue().compact(cfg.compactGarbageRatio(), cfg.compactMinBytes());
                if (r != null) plugin.getLogger().info("[AWRE] compacted terrain cache of world " + e.getKey() + ": " + r.recordsBefore() + " -> " + r.recordsAfter() + " records, " + r.bytesBefore() + " -> " + r.bytesAfter() + " bytes");
            }
        }, 600L, 600L);   // every 30 s
    }

    private static String fingerprint(World w) {
        String gen = w.getGenerator() == null ? "vanilla" : w.getGenerator().getClass().getName();
        return "hf|" + w.getUID() + "|" + w.getSeed() + "|" + w.getMinHeight() + ":" + w.getMaxHeight() + "|" + gen;
    }

    void stop() {
        if (task != null) task.cancel();
        if (flushTask != null) flushTask.cancel();
        if (capture != null) capture.stop();
        entries.clear();
        for (AwreWorldTerrain t : terrains.values()) t.close();
        terrains.clear();
    }

    /** Terrain for a world (created on first use); in synthetic mode every world shares the same terrain. Main thread or async. */
    AwreWorldTerrain terrainFor(World w) {
        AwreConfig c = cfg;
        if (c == null) return null;
        UUID key = c.streamCaptured() ? w.getUID() : SYNTHETIC_KEY;
        AwreWorldTerrain existing = terrains.get(key);
        if (existing != null) return existing;
        synchronized (this) {
            existing = terrains.get(key);
            if (existing != null) return existing;
            TerrainStore store = new TerrainStore(model.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
            AwreWorldTerrain t;
            if (c.streamCaptured()) {
                final AwreWorldTerrain ct = new AwreWorldTerrain(store, new MaterialPalette(), w.getMinHeight(), w.getMaxHeight() - 1);
                t = ct;
                terrains.put(key, t);
                final String fp = fingerprint(w), name = w.getName();
                final java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("terrain").resolve(w.getUID() + ".hfl");
                final boolean persist = c.streamPersist(), importHf = c.streamImport();
                final java.nio.file.Path horizonFile = plugin.getDataFolder().getParentFile().toPath().resolve("HorizonField").resolve("cache").resolve(w.getUID() + ".hfl");
                // replaying a large cache takes a while: do it off the main thread; the world streams once it is ready
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    TerrainPersistence per = null;
                    if (persist) {
                        try {
                            if (importHf && !java.nio.file.Files.exists(file) && java.nio.file.Files.isRegularFile(horizonFile)) {
                                java.nio.file.Files.createDirectories(file.getParent());
                                java.nio.file.Files.copy(horizonFile, file);   // a torn tail (HorizonField still writing) is cut off by the CRC check on open
                                plugin.getLogger().info("[AWRE] imported HorizonField cache for '" + name + "'");
                            }
                            per = TerrainPersistence.open(file, fp, store, ct.palette, metrics);
                            plugin.getLogger().info("[AWRE] terrain cache for '" + name + "': " + (per.report().freshStart() ? "fresh (" + per.report().reason() + ")" : "restored " + per.report().tiles() + " chunks")
                                    + ", discarded " + per.report().discardedBytes() + " bytes");
                        } catch (java.io.IOException | RuntimeException e) {
                            plugin.getLogger().warning("[AWRE] terrain cache for '" + name + "' unavailable, continuing in memory only: " + e);
                            per = null;
                        }
                    }
                    ct.persistence = per;
                    ct.ingest = new ChunkIngest(store, ct.palette, 4096, 1, metrics, e -> plugin.getLogger().warning("[AWRE] ingest error: " + e), per);
                    ct.ready = true;
                    plugin.getServer().getScheduler().runTask(plugin, () -> { if (capture != null) capture.enqueueLoaded(w); });
                    plugin.getLogger().info("[AWRE] capturing loaded chunks of '" + name + "' (OBSERVED" + (per != null ? ", persisted" : ", in memory only") + ")");
                });
            } else {
                t = new AwreWorldTerrain(store, SyntheticTerrain.palette(), 0, 0);
                terrains.put(key, t);
                final AwreWorldTerrain tt = t; final int r = c.syntheticChunkRadius();
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    long t0 = System.nanoTime();
                    int n = r > 0 ? SyntheticTerrain.fill(tt.store, -r, -r, r, r, SYNTHETIC_SEED) : 0;
                    tt.ready = true;
                    plugin.getLogger().info("[AWRE] stream terrain ready: " + n + " synthetic chunks (PREDICTED, not observed) in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                });
            }
            return t;
        }
    }

    void attach(Player p, ServerSession s, AwreConfig c) {
        this.cfg = c;
        World w = p.getWorld();
        String dim = AwreChannel.wireDimension(w);
        UUID wid = w.getUID();
        AwreWorldTerrain t = terrainFor(w);
        StreamConfig sc = new StreamConfig(c.streamRadius(), c.streamBytesPerSecond(), 65_536, 0.1, c.streamMaxHeldTiles(), 16, 256, 3);
        entries.put(p.getUniqueId(), new Entry(new PlayerStreamer(s, t.store, t.palette, new LodPlanner(model), sc,
                wid.getMostSignificantBits(), wid.getLeastSignificantBits(), dim, System::nanoTime), wid));
        plugin.getLogger().info("[AWRE] stream attached: " + p.getName() + " radius=" + (int) c.streamRadius() + " source=" + (c.streamCaptured() ? "captured" : "synthetic"));
    }

    void detach(UUID id) { entries.remove(id); }

    PlayerStreamer.Stats stats(UUID id) { Entry e = entries.get(id); return e == null ? null : e.streamer.stats(); }

    /** Chunks held by the terrain this player streams from (0 if not attached). */
    int terrainChunks(Player p) { AwreWorldTerrain t = terrainFor(p.getWorld()); return t == null ? 0 : t.store.chunkCount(); }

    private void tickAll() {
        boolean log = ++rounds % 50 == 0;                       // every 5 s
        for (Map.Entry<UUID, Entry> me : entries.entrySet()) {
            Player p = Bukkit.getPlayer(me.getKey());
            Entry e = me.getValue();
            if (p == null || !p.isOnline()) { entries.remove(me.getKey()); continue; }
            if (!p.getWorld().getUID().equals(e.world)) {       // world change: new terrain, new shadow
                ServerSession s = channel.session(p);
                if (s != null && s.state() == ServerSession.State.ACTIVE) attach(p, s, cfg); else detach(me.getKey());
                continue;
            }
            AwreWorldTerrain t = terrainFor(p.getWorld());
            if (t == null || !t.ready) continue;
            if (log) {
                PlayerStreamer.Stats st = e.streamer.stats();
                if (!st.equals(e.lastLogged)) {
                    e.lastLogged = st;
                    plugin.getLogger().info("[AWRE] stream " + p.getName() + ": leaves=" + st.plannedLeaves() + " held=" + st.held() + " pending=" + st.pending()
                            + " sent=" + st.sent() + " wire=" + st.wireBytes() + "B holes=" + st.holes() + " noData=" + st.noData() + " dropped=" + st.dropped() + " failures=" + st.failures());
                }
            }
            if (!e.busy.compareAndSet(false, true)) continue;   // previous job still running
            Location l = p.getLocation();
            double x = l.getX(), z = l.getZ(), yaw = Math.toRadians(l.getYaw());
            double heading = Math.atan2(Math.cos(yaw), -Math.sin(yaw));   // Minecraft yaw 0 = +z (south), 90 = -x (west)
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                List<byte[]> frames;
                try { frames = e.streamer.tick(x, z, heading); }
                catch (RuntimeException ex) { plugin.getLogger().warning("[AWRE] stream tick failed: " + ex); frames = List.of(); }
                final List<byte[]> out = frames;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try { if (p.isOnline()) for (byte[] f : out) channel.send(p, f); }
                    finally { e.busy.set(false); }
                });
            });
        }
    }
}
