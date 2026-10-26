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
    private boolean storeFullWarned;
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
            capture = new AwreCapture(plugin, this, c.revalidateSeconds(), c.captureBudgetMicros() * 1000L);
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
        String finalCapture = captureStats();                   // last capture/timing line goes to the log so CI and operators can read it without /awre
        if (finalCapture != null) plugin.getLogger().info("[AWRE] " + finalCapture + " (final)");
        if (task != null) task.cancel();
        if (flushTask != null) flushTask.cancel();
        if (capture != null) capture.stop();
        entries.clear();
        for (AwreWorldTerrain t : terrains.values()) t.close();
        terrains.clear();
    }

    /** Terrain for a world (created on first use); in synthetic mode every world shares the same terrain. Main thread or async. */
    /** True for worlds that are deliberately not captured or streamed (a ceiling would be streamed as terrain; see WorldEligibility). Cheap: safe to call per event. */
    boolean skipsWorld(World w) {
        AwreConfig c = cfg;
        if (c == null || !dev.awre.stream.WorldEligibility.skipsStreaming(w.getEnvironment().name(), c.skipCeilingWorlds())) return false;
        if (skippedLogged.add(w.getUID())) plugin.getLogger().info("[AWRE] world '" + w.getName() + "' (" + w.getEnvironment() + ") has a ceiling: its roof would be streamed as terrain, so it is not captured or streamed (stream.skip-ceiling-worlds: false overrides)");
        return true;
    }

    private final java.util.Set<UUID> skippedLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    AwreWorldTerrain terrainFor(World w) {
        AwreConfig c = cfg;
        if (c == null || skipsWorld(w)) return null;
        UUID key = c.streamCaptured() ? w.getUID() : SYNTHETIC_KEY;
        AwreWorldTerrain existing = terrains.get(key);
        if (existing != null) return existing;
        synchronized (this) {
            existing = terrains.get(key);
            if (existing != null) return existing;
            TerrainStore store = new TerrainStore(model.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
            applyStoreBudget(store, w.getName());
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
        if (t == null) { entries.remove(p.getUniqueId()); return; }      // not streamed in this world (e.g. a ceiling): no stream, and the previous world's stream stops
        StreamConfig sc = new StreamConfig(c.streamRadius(), c.streamBytesPerSecond(), 65_536, 0.1, c.streamMaxHeldTiles(), 16, 256, 3);
        entries.put(p.getUniqueId(), new Entry(new PlayerStreamer(s, t.store, t.palette, new LodPlanner(model), sc,
                wid.getMostSignificantBits(), wid.getLeastSignificantBits(), dim, System::nanoTime, retryCooldownNanos()), wid));
        plugin.getLogger().info("[AWRE] stream attached: " + p.getName() + " radius=" + (int) c.streamRadius() + " source=" + (c.streamCaptured() ? "captured" : "synthetic"));
    }

    boolean detach(UUID id) { return entries.remove(id) != null; }

    PlayerStreamer.Stats stats(UUID id) { Entry e = entries.get(id); return e == null ? null : e.streamer.stats(); }

    /** Chunks held by the terrain this player streams from (0 if not attached). */
    /** Approximate heap held by the observed chunks of the player's world (see TerrainStore#approxChunkBytes). */
    long terrainChunkBytes(Player p) { AwreWorldTerrain t = terrainFor(p.getWorld()); return t == null ? 0 : t.store.approxChunkBytes(); }

    private long retryCooldownNanos = -1;

    /** {@code stream.retry-cooldown-seconds} (5..3600, default 60) as nanoseconds: how long a tile that a client keeps failing is held back before one more attempt (doubling, up to 16x). Read once. */
    private long retryCooldownNanos() {
        if (retryCooldownNanos < 0) {
            int sec = plugin.getConfig().getInt("stream.retry-cooldown-seconds", 60);
            if (sec < 5 || sec > 3600) { plugin.getLogger().warning("[AWRE] stream.retry-cooldown-seconds must be 5..3600; using 60"); sec = 60; }
            retryCooldownNanos = sec * 1_000_000_000L;
        }
        return retryCooldownNanos;
    }

    /** Sets the observed-chunk cap of a new world store from {@code stream.max-store-mib} (invalid values fall back to automatic) and logs the result. */
    private void applyStoreBudget(TerrainStore store, String world) {
        int mib = plugin.getConfig().getInt("stream.max-store-mib", 0);
        dev.awre.stream.StoreBudget.Resolved r;
        try { r = dev.awre.stream.StoreBudget.resolve(mib, Runtime.getRuntime().maxMemory()); }
        catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[AWRE] " + e.getMessage() + "; using automatic");
            r = dev.awre.stream.StoreBudget.resolve(0, Runtime.getRuntime().maxMemory());
        }
        store.setMaxChunks(r.maxChunks());
        String pol = plugin.getConfig().getString("stream.store-full-policy", "evict-oldest");
        TerrainStore.FullPolicy fp = "refuse-new".equalsIgnoreCase(pol) ? TerrainStore.FullPolicy.REFUSE_NEW : "evict-oldest".equalsIgnoreCase(pol) ? TerrainStore.FullPolicy.EVICT_OLDEST : null;
        if (fp == null) { plugin.getLogger().warning("[AWRE] stream.store-full-policy must be evict-oldest or refuse-new (got '" + pol + "'); using evict-oldest"); fp = TerrainStore.FullPolicy.EVICT_OLDEST; }
        store.setFullPolicy(fp);
        plugin.getLogger().info("[AWRE] terrain store full policy for '" + world + "': " + (fp == TerrainStore.FullPolicy.EVICT_OLDEST ? "evict-oldest" : "refuse-new"));
        plugin.getLogger().info("[AWRE] terrain store cap for '" + world + "': " + r.maxChunks() + " chunks (~" + (r.budgetBytes() >> 20) + " MiB, " + (r.auto() ? "automatic" : "configured") + ")");
    }

    /** Total chunks refused because a world's terrain store was full (0 = the cap was never hit). */
    /** Total chunks removed to admit newer ones (stream.store-full-policy: evict-oldest). */
    long storeEvicted() { long n = 0; for (AwreWorldTerrain t : terrains.values()) n += t.store.evictedChunks(); return n; }

    long storeRejected() { long n = 0; for (AwreWorldTerrain t : terrains.values()) n += t.store.rejectedChunks(); return n; }

    /** Capture backlog (requested / handled / retried / waiting / queued / peak waiting), or null when capture is off. */
    String captureStats() {
        AwreCapture c = capture;
        if (c == null) return null;
        var st = c.stats();
        return "capture: requested=" + st.requested() + " handled=" + st.handled() + " retried=" + st.retried() + " waiting=" + st.waiting() + " queued=" + st.queued() + " peakWaiting=" + st.maxWaiting() + " " + c.timingStats() + " storeRejected=" + storeRejected() + " storeEvicted=" + storeEvicted();
    }

    int terrainChunks(Player p) { AwreWorldTerrain t = terrainFor(p.getWorld()); return t == null ? 0 : t.store.chunkCount(); }

    private void tickAll() {
        boolean log = ++rounds % 50 == 0;                       // every 5 s
        if (rounds % 600 == 0) {
            String cs = captureStats(); if (cs != null) plugin.getLogger().info("[AWRE] " + cs);
            long full = storeRejected();
            if (full > 0 && !storeFullWarned) { storeFullWarned = true; plugin.getLogger().warning("[AWRE] terrain store is full: " + full + " chunk(s) at new coordinates were not captured. Raise stream.max-store-mib (or give the server more heap) to capture more distant terrain; stored chunks keep updating."); }
        }   // once a minute
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
