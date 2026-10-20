package dev.horizonfield.paper;

import dev.horizonfield.core.Metrics;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HorizonFieldPlugin extends JavaPlugin {
    private CaptureService capture;
    private ProbeService probes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        HorizonConfig cfg = HorizonConfig.load(getConfig());
        Metrics metrics = new Metrics();
        capture = new CaptureService(this, cfg, metrics);
        probes = new ProbeService(this);
        getServer().getPluginManager().registerEvents(new HorizonListener(capture), this);
        PluginCommand pc = Objects.requireNonNull(getCommand("horizon"), "command 'horizon' missing from plugin.yml");
        HorizonFieldCommand handler = new HorizonFieldCommand(this, cfg, capture, probes, metrics);
        pc.setExecutor(handler);
        pc.setTabCompleter(handler);
        capture.start();
        // Chunks that were already loaded before this plugin enabled (e.g. /reload-less late enable): queue them once.
        getServer().getWorlds().forEach(w -> {
            for (var c : w.getLoadedChunks()) capture.request(w, c.getX(), c.getZ());
        });
        getLogger().info("HorizonField enabled (scaffold: observation + diagnostics, no distant rendering). Java "
                + Runtime.version().feature() + ", workers=" + cfg.analysisThreads());
    }

    @Override
    public void onDisable() {
        if (probes != null) probes.clear();
        if (capture != null) capture.shutdown();
        getLogger().info("HorizonField disabled; workers stopped and caches flushed.");
    }
}
