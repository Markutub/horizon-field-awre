package dev.awre.paper;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** G0 entry point: registers the awre:main channel and the diagnostics command. Does not touch chunks or the world. */
public final class AwrePlugin extends JavaPlugin {
    private AwreChannel channel;
    private AwreStreaming streaming;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        AwreConfig cfg = AwreConfig.load(getConfig(), getLogger());
        channel = new AwreChannel(this, cfg);
        channel.register();
        streaming = new AwreStreaming(this, channel);
        channel.setStreaming(streaming);
        streaming.start(cfg);
        PluginCommand pc = Objects.requireNonNull(getCommand("awre"), "command 'awre' missing from plugin.yml");
        AwreCommand handler = new AwreCommand(this, channel);
        pc.setExecutor(handler);
        pc.setTabCompleter(handler);
        getLogger().info("AWRE G0 enabled: channel awre:main, max frame " + cfg.limits().maxFrameBytes() + " B, enabled=" + cfg.enabled()
                + ", Java " + Runtime.version().feature());
    }

    @Override
    public void onDisable() {
        if (streaming != null) streaming.stop();
        if (channel != null) channel.unregister();
        getLogger().info("AWRE disabled; channel unregistered, sessions dropped.");
    }
}
