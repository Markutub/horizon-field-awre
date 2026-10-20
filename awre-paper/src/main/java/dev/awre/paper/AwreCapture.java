package dev.awre.paper;

import dev.horizonfield.core.BoundedTaskQueue;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.UUID;

/**
 * Observes ONLY chunks the server has already loaded (never loads or generates one) and feeds them to the per-world
 * {@link AwreWorldTerrain} ingest. SOURCE-ONLY (never compiled against Paper yet). Same shape as horizon-paper's CaptureService:
 * handlers enqueue coordinates (O(1)); a time-budgeted main-thread drain takes ChunkSnapshots; analysis runs on worker threads.
 * Persistence (stream.persist) is handled by AwreStreaming/TerrainPersistence: the terrain is replayed from disk after a restart.
 */
final class AwreCapture implements Listener {
    private record Ref(UUID world, int cx, int cz) { }
    private static final int MAX_PER_TICK = 2;
    private static final long BUDGET_NANOS = 500_000L;   // 0.5 ms per tick on the main thread

    private final JavaPlugin plugin;
    private final AwreStreaming streaming;
    private final BoundedTaskQueue<Ref> queue = new BoundedTaskQueue<>(4096);
    private BukkitTask task;

    AwreCapture(JavaPlugin plugin, AwreStreaming streaming) { this.plugin = plugin; this.streaming = streaming; }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    void stop() { if (task != null) task.cancel(); queue.close(); }

    void enqueueLoaded(World w) { for (Chunk c : w.getLoadedChunks()) request(w, c.getX(), c.getZ()); }

    void request(World w, int cx, int cz) {
        Ref r = new Ref(w.getUID(), cx, cz);
        queue.offer(r, nearestPlayerDistSq(w, cx * 16 + 8, cz * 16 + 8), r);
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

    private void tick() {
        queue.drain(ref -> {
            try { snapshot(ref); } catch (RuntimeException e) { plugin.getLogger().warning("[AWRE] snapshot failed for " + ref + ": " + e); }
        }, BUDGET_NANOS, MAX_PER_TICK);
    }

    private void snapshot(Ref ref) {
        World w = plugin.getServer().getWorld(ref.world());
        if (w == null || !w.isChunkLoaded(ref.cx(), ref.cz())) return;
        AwreWorldTerrain t = streaming.terrainFor(w);
        if (t == null || t.ingest == null || !t.ready) return;
        ChunkSnapshot snap = w.getChunkAt(ref.cx(), ref.cz()).getChunkSnapshot(true, false, false);   // already loaded: no load/generation
        t.ingest.submit(new AwreChunkView(snap, t.minY, t.maxY), nearestPlayerDistSq(w, ref.cx() * 16 + 8, ref.cz() * 16 + 8));
    }

    /** Worlds loaded after the plugin started (multiworld plugins): set their terrain up right away. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent e) { streaming.terrainFor(e.getWorld()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) { request(e.getWorld(), e.getChunk().getX(), e.getChunk().getZ()); }

    // the queue drains on a later tick, after the change has been applied
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { request(e.getBlock().getWorld(), e.getBlock().getX() >> 4, e.getBlock().getZ() >> 4); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { request(e.getBlock().getWorld(), e.getBlock().getX() >> 4, e.getBlock().getZ() >> 4); }
}
