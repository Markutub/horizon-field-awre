package dev.awre.paper;

import dev.horizonfield.core.CaptureBacklog;
import dev.horizonfield.core.TimingWindow;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *
 * <p>Nothing requested here may be forgotten: requests go through a {@link CaptureBacklog} (a bounded queue alone silently drops the
 * farthest chunks when more than its capacity are loaded, and a chunk whose request was dropped is never observed again). The chunk
 * is only observed at LOAD and when something changes it, so besides place/break the handlers below cover the other common ways
 * terrain changes (explosions, pistons, fire, leaf decay, tree growth, falling blocks, growth/spread/fade), and
 * {@code stream.revalidate-seconds} re-observes every loaded chunk periodically for everything no event reports (WorldEdit, fluids,
 * other plugins). Re-observing an unchanged chunk costs a snapshot and an analysis but creates no revision and writes nothing.
 */
final class AwreCapture implements Listener {
    private record Ref(UUID world, int cx, int cz) { }
    private static final int MAX_PER_TICK = 2;
    private static final long BUDGET_NANOS = 500_000L;   // 0.5 ms per tick on the main thread

    private final JavaPlugin plugin;
    private final AwreStreaming streaming;
    private static final int INGEST_HIGH_WATER = 3072;       // do not take snapshots while the analysis queue (capacity 4096) is this full
    private final CaptureBacklog<Ref> backlog = new CaptureBacklog<>(4096);
    private final long revalidateTicks;
    private long sinceSweep;
    // Bounded main-thread cost instrumentation (last 2048 samples each; see docs/MEASUREMENT_PLAN.md). Measures, does not tune.
    private final TimingWindow snapshotTimes = new TimingWindow(2048);   // one ChunkSnapshot + submit
    private final TimingWindow tickTimes = new TimingWindow(2048);       // whole tick() body: sweep enqueue + pump + drain
    private volatile int lastSweepChunks;
    private volatile long lastSweepNanos;
    private BukkitTask task;

    /** @param revalidateSeconds 0 = never re-observe loaded chunks that no event reported as changed */
    AwreCapture(JavaPlugin plugin, AwreStreaming streaming, int revalidateSeconds) {
        this.plugin = plugin; this.streaming = streaming; this.revalidateTicks = revalidateSeconds * 20L;
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    void stop() { if (task != null) task.cancel(); backlog.close(); }

    CaptureBacklog.Stats stats() { return backlog.stats(); }

    /** Main-thread cost line: snapshot and whole-tick percentiles plus the last revalidation sweep (enqueue work only). */
    String timingStats() {
        return "snapshot[" + snapshotTimes.summary().format() + "] tick[" + tickTimes.summary().format() + "] lastSweep chunks="
                + lastSweepChunks + " enqueueMs=" + String.format(java.util.Locale.ROOT, "%.2f", lastSweepNanos / 1e6);
    }

    /** @return how many loaded chunks were requested */
    int enqueueLoaded(World w) { Chunk[] loaded = w.getLoadedChunks(); for (Chunk c : loaded) request(w, c.getX(), c.getZ()); return loaded.length; }

    /** O(1): safe to call from any event, however often it fires; priorities are computed when the work is queued. */
    void request(World w, int cx, int cz) { backlog.request(new Ref(w.getUID(), cx, cz)); }

    private void requestBlock(Block b) { if (b != null) request(b.getWorld(), b.getX() >> 4, b.getZ() >> 4); }

    private double priority(Ref r) {
        World w = plugin.getServer().getWorld(r.world());
        return w == null ? 1e18 : nearestPlayerDistSq(w, r.cx() * 16 + 8, r.cz() * 16 + 8);
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
        long t0 = System.nanoTime();
        try { tickBody(); } finally { tickTimes.record(System.nanoTime() - t0); }
    }

    private void tickBody() {
        if (revalidateTicks > 0 && ++sinceSweep >= revalidateTicks) {
            sinceSweep = 0;
            long s0 = System.nanoTime(); int n = 0;
            for (World w : plugin.getServer().getWorlds()) {
                AwreWorldTerrain t = streaming.terrainFor(w);
                if (t != null && t.ready) n += enqueueLoaded(w);
            }
            lastSweepChunks = n; lastSweepNanos = System.nanoTime() - s0;
        }
        backlog.pump(this::priority);
        backlog.drain(ref -> {
            long s0 = System.nanoTime();
            try { return snapshot(ref); }
            catch (RuntimeException e) { plugin.getLogger().warning("[AWRE] snapshot failed for " + ref + ": " + e); return true; }   // a chunk that throws must not be retried forever
            finally { snapshotTimes.record(System.nanoTime() - s0); }
        }, BUDGET_NANOS, MAX_PER_TICK);
    }

    /** @return true when the request is finished (observed, or nothing to observe), false to try again on a later tick */
    private boolean snapshot(Ref ref) {
        World w = plugin.getServer().getWorld(ref.world());
        if (w == null || !w.isChunkLoaded(ref.cx(), ref.cz())) return true;   // gone: it is requested again when it loads
        AwreWorldTerrain t = streaming.terrainFor(w);
        if (t == null || !t.ready || t.ingest == null) return true;            // enqueueLoaded() re-requests every loaded chunk once the terrain is ready
        if (t.ingest.queued() >= INGEST_HIGH_WATER) return false;              // analysis is behind: do not waste a snapshot, retry later
        ChunkSnapshot snap = w.getChunkAt(ref.cx(), ref.cz()).getChunkSnapshot(true, true, false);   // already loaded: no load/generation; biomes included for tinting
        return t.ingest.submit(new AwreChunkView(snap, t.minY, t.maxY), priority(ref));
    }

    /** Worlds loaded after the plugin started (multiworld plugins): set their terrain up right away. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent e) { streaming.terrainFor(e.getWorld()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) { request(e.getWorld(), e.getChunk().getX(), e.getChunk().getZ()); }

    // Every handler below only records "this chunk changed"; the snapshot is taken on a later tick, after the change was applied.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { requestBlock(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { requestBlock(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { requestAll(e.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { requestAll(e.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) { requestPiston(e.getBlock(), e.getBlocks(), e.getDirection()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) { requestPiston(e.getBlock(), e.getBlocks(), e.getDirection()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { requestBlock(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent e) { requestBlock(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { requestBlock(e.getBlock()); }

    /** Also covers BlockFormEvent and BlockSpreadEvent (they extend BlockGrowEvent): crops, cactus, snow, ice, grass spread. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) { requestBlock(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) { requestBlock(e.getBlock()); }   // falling blocks, endermen, farmland trampling

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) {
        Set<Long> seen = new HashSet<>();
        for (BlockState s : e.getBlocks()) if (seen.add(key(s.getX() >> 4, s.getZ() >> 4))) request(s.getWorld(), s.getX() >> 4, s.getZ() >> 4);
    }

    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    /** One request per distinct chunk of the list (an explosion can name hundreds of blocks in a few chunks). */
    private void requestAll(List<Block> blocks) {
        Set<Long> seen = new HashSet<>();
        for (Block b : blocks) if (seen.add(key(b.getX() >> 4, b.getZ() >> 4))) requestBlock(b);
    }

    /** The piston, every moved block and the cell each one moves into (which may be in the next chunk). */
    private void requestPiston(Block piston, List<Block> moved, org.bukkit.block.BlockFace dir) {
        Set<Long> seen = new HashSet<>();
        if (seen.add(key(piston.getX() >> 4, piston.getZ() >> 4))) requestBlock(piston);
        for (Block b : moved) {
            Block to = b.getRelative(dir);
            if (seen.add(key(b.getX() >> 4, b.getZ() >> 4))) requestBlock(b);
            if (seen.add(key(to.getX() >> 4, to.getZ() >> 4))) requestBlock(to);
        }
    }
}
