package dev.horizonfield.paper;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/** Feeds the capture queue. Handlers only enqueue coordinates (O(1)); no world access beyond coordinates. */
final class HorizonListener implements Listener {
    private final CaptureService capture;

    HorizonListener(CaptureService capture) { this.capture = capture; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        capture.request(e.getWorld(), e.getChunk().getX(), e.getChunk().getZ());
    }

    // Block events: the queue is drained on a later tick, after the change has been applied.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        capture.request(e.getBlock().getWorld(), e.getBlock().getX() >> 4, e.getBlock().getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        capture.request(e.getBlock().getWorld(), e.getBlock().getX() >> 4, e.getBlock().getZ() >> 4);
    }
}
