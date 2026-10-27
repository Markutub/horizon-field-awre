package dev.awre.paper;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** G0 entry point: registers the awre:main channel and the diagnostics command. Does not touch chunks or the world. */
public final class AwrePlugin extends JavaPlugin {
    private AwreChannel channel;
    private AwreStreaming streaming;
    private AwreMspt mspt;
    private AwreReport report;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        AwreConfig cfg = AwreConfig.load(getConfig(), getLogger());
        channel = new AwreChannel(this, cfg);
        channel.register();
        streaming = new AwreStreaming(this, channel);
        channel.setStreaming(streaming);
        streaming.start(cfg);
        int msptSeconds = getConfig().getInt("diagnostics.mspt-seconds", 0);
        if (msptSeconds > 0) { mspt = new AwreMspt(this, Math.min(msptSeconds, 3600)); mspt.start(); }
        int reportSeconds = getConfig().getInt("diagnostics.report-seconds", 0);
        report = new AwreReport(this, channel, streaming, mspt, reportSeconds <= 0 ? 0 : Math.max(10, Math.min(reportSeconds, 86400)));
        report.start();
        PluginCommand pc = Objects.requireNonNull(getCommand("awre"), "command 'awre' missing from plugin.yml");
        AwreCommand handler = new AwreCommand(this, channel);
        handler.setReport(report);
        pc.setExecutor(handler);
        pc.setTabCompleter(handler);
        getLogger().info("AWRE G0 enabled: channel awre:main, max frame " + cfg.limits().maxFrameBytes() + " B, enabled=" + cfg.enabled()
                + ", Java " + Runtime.version().feature());
    }

    @Override
    public void onDisable() {
        if (mspt != null) mspt.stop();
        if (report != null) report.stop();                 // final result line while streaming stats still exist
        if (streaming != null) streaming.stop();
        if (channel != null) channel.unregister();
        getLogger().info("AWRE disabled; channel unregistered, sessions dropped.");
    }
}
