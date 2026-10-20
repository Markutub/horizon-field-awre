package dev.horizonfield.paper;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Phase-1 experiment E1/E2 helper. Spawns a ring of tall pillars (Block Display entities) at a chosen distance, visible
 * ONLY to the requesting player (setVisibleByDefault(false) + Player#showEntity). It uses public API only, so pillars exist
 * only inside server-loaded chunks. To test "beyond client render distance", set the CLIENT render distance low (e.g. 4)
 * while the server has more chunks loaded, then place pillars farther than the client's fog end.
 * Everything spawned is non-persistent and removed on a timer and on plugin disable.
 */
final class ProbeService {
    private final JavaPlugin plugin;
    private final List<Entity> live = new ArrayList<>();

    ProbeService(JavaPlugin plugin) { this.plugin = plugin; }

    record Result(int spawned, int skippedUnloaded) { }

    /** Main thread only. */
    Result spawnRing(Player player, double distance, float viewRange, int pillars, int seconds, Material material) {
        World w = player.getWorld();
        Location c = player.getLocation();
        int spawned = 0, skipped = 0;
        for (int i = 0; i < pillars; i++) {
            double a = 2 * Math.PI * i / pillars;
            double x = c.getX() + Math.cos(a) * distance, z = c.getZ() + Math.sin(a) * distance;
            if (!w.isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4)) { skipped++; continue; }
            Location at = new Location(w, x, c.getY() - 2, z);
            BlockDisplay d = w.spawn(at, BlockDisplay.class, e -> {
                e.setBlock(Bukkit.createBlockData(material));
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setViewRange(viewRange);
                e.setDisplayWidth(16f);
                e.setDisplayHeight(80f);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTransformation(new Transformation(new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(6, 80, 6), new Quaternionf()));
            });
            player.showEntity(plugin, d);
            live.add(d);
            spawned++;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, this::clear, seconds * 20L);
        return new Result(spawned, skipped);
    }

    /** Main thread only. */
    void clear() {
        for (Entity e : live) if (e.isValid()) e.remove();
        live.clear();
    }
}
