package dev.awre.paper;

import dev.awre.protocol.FaultInjector;
import dev.horizonfield.core.lod.LodModel;
import dev.horizonfield.core.lod.LodPlan;
import dev.horizonfield.core.lod.LodPlanner;
import dev.awre.protocol.ServerSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AwreCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("status", "stats", "sendtest", "fragtest", "lodplan", "stream", "sendbad", "debug", "reload");
    private final JavaPlugin plugin;
    private final AwreChannel channel;

    public AwreCommand(JavaPlugin plugin, AwreChannel channel) { this.plugin = plugin; this.channel = channel; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!sender.hasPermission("awre.admin")) { sender.sendMessage("You do not have permission."); return true; }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "stats" -> stats(sender);
            case "sendtest" -> withSession(sender, (p, s) -> sender.sendMessage("test tile: " + channel.sendTestTile(p, s)));
            case "fragtest" -> {
                int side = 128;
                if (a.length > 1) { try { side = Integer.parseInt(a[1]); } catch (NumberFormatException e) { sender.sendMessage("usage: /awre fragtest [side 16..256]"); return true; } }
                if (side < 16 || side > 256) { sender.sendMessage("side must be 16..256"); return true; }
                final int sideF = side;
                withSession(sender, (p, s) -> {
                    var r = channel.sendNoiseTile(p, s, sideF);
                    sender.sendMessage("noise tile side=" + sideF + " (" + (sideF * sideF * 2) + " raw bytes): " + r.result() + ", frames=" + r.frames().size()
                            + ". Client log should show 'verified OK'; /awre status should then show inFlight=0.");
                });
            }
            case "lodplan" -> lodPlan(sender, a);
            case "stream" -> stream(sender, a);
            case "sendbad" -> {
                if (a.length < 2 || !FaultInjector.KINDS.contains(a[1].toLowerCase(Locale.ROOT))) { sender.sendMessage("usage: /awre sendbad " + FaultInjector.KINDS); return true; }
                withSession(sender, (p, s) -> {
                    List<byte[]> frames = s.faultFrames(a[1].toLowerCase(Locale.ROOT));
                    frames.forEach(f -> channel.send(p, f));
                    sender.sendMessage("sent " + frames.size() + " fault frame(s) '" + a[1] + "'; the client must log a rejection and stay connected");
                });
            }
            case "debug" -> {
                boolean on = a.length > 1 ? a[1].equalsIgnoreCase("on") : !channel.debug();
                channel.setDebug(on);
                sender.sendMessage("AWRE debug logging " + (on ? "ON" : "OFF"));
            }
            case "reload" -> {
                plugin.reloadConfig();
                channel.reload(AwreConfig.load(plugin.getConfig(), plugin.getLogger()));
                sender.sendMessage("AWRE config reloaded (limits apply to new sessions; players must rejoin to renegotiate).");
            }
            default -> sender.sendMessage("usage: /awre " + String.join("|", SUBS));
        }
        return true;
    }

    private interface WithSession { void run(Player p, ServerSession s); }

    private void withSession(CommandSender sender, WithSession r) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Run this as a player."); return; }
        ServerSession s = channel.session(p);
        if (s == null || s.state() != ServerSession.State.ACTIVE) { sender.sendMessage("No ACTIVE AWRE session (vanilla client, or handshake not done). Use /awre status."); return; }
        r.run(p, s);
    }

    private void stream(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Run this as a player."); return; }
        AwreStreaming st = channel.streaming();
        String mode = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
        ServerSession s = channel.session(p);
        switch (mode) {
            case "on" -> {
                if (s == null || s.state() != ServerSession.State.ACTIVE) { sender.sendMessage("No ACTIVE AWRE session. Use /awre status."); return; }
                st.attach(p, s, channel.config());
                sender.sendMessage("stream on (terrain comes from stream.synthetic-chunk-radius in config.yml; 0 = nothing to stream)");
            }
            case "off" -> { st.detach(p.getUniqueId()); sender.sendMessage("stream off (the client keeps what it holds until it disconnects)"); }
            default -> {
                var x = st.stats(p.getUniqueId());
                sender.sendMessage("terrain chunks in memory: " + st.terrainChunks(p));
                sender.sendMessage(x == null ? "stream: not attached" : "stream: leaves=" + x.plannedLeaves() + " held=" + x.held() + " pending=" + x.pending() + " sent=" + x.sent()
                        + " wire=" + x.wireBytes() + "B holes=" + x.holes() + " noData=" + x.noData() + " dropped=" + x.dropped() + " failures=" + x.failures() + " replans=" + x.replans());
            }
        }
    }

    /** Plans LOD tiles around the player OFF the main thread (planning takes tens of ms; see docs/lodplanbench-output.txt). */
    private void lodPlan(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Run this as a player."); return; }
        double radius = 4000;
        if (a.length > 1) { try { radius = Double.parseDouble(a[1]); } catch (NumberFormatException e) { sender.sendMessage("usage: /awre lodplan [radius 256..16000]"); return; } }
        if (radius < 256 || radius > 16000) { sender.sendMessage("radius must be 256..16000"); return; }
        final double r = radius, x = p.getLocation().getX(), z = p.getLocation().getZ();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            LodModel m = LodModel.defaults();
            long t0 = System.nanoTime();
            LodPlan plan = new LodPlanner(m).plan(x, z, r, null);
            double ms = (System.nanoTime() - t0) / 1e6;
            int[] c = plan.countByLevel(m.maxLod());
            StringBuilder sb = new StringBuilder();
            for (int l = 0; l <= m.maxLod(); l++) sb.append(" L").append(l).append('=').append(c[l]);
            sender.sendMessage("LOD plan R=" + (int) r + ": " + plan.leaves().size() + " tiles in " + String.format("%.1f", ms) + " ms (async) |" + sb
                    + " | model: fov " + m.fovDegrees() + ", " + m.viewportHeightPx() + "px, tolerance " + m.tolerancePx() + "px, kappa " + m.kappa() + " (assumed)");
        });
    }

    private void status(CommandSender sender) {
        sender.sendMessage("AWRE G0+G1 (fragmentation, LOD plan) | enabled=" + channel.config().enabled() + " sessions=" + channel.sessionCount());
        if (!(sender instanceof Player p)) return;
        ServerSession s = channel.session(p);
        sender.sendMessage("client announced channel awre:main via minecraft:register: " + channel.clientListens(p));
        if (s == null) { sender.sendMessage("your session: none (no AWRE message received: vanilla client or mod absent)"); return; }
        sender.sendMessage("your session: " + s.state() + " v" + s.negotiatedVersion() + " mod=" + s.clientModVersion() + " maxFrame=" + s.negotiatedFrame());
        sender.sendMessage("  tilesSent=" + s.tilesSent() + " acksOk=" + s.acksOk() + " acksInvalid=" + s.acksInvalid() + " acksStale=" + s.acksStale()
                + " inFlight=" + s.inFlight() + " strikes=" + s.strikes() + (s.lastError().isEmpty() ? "" : " lastError=" + s.lastError()));
    }

    private void stats(CommandSender sender) {
        sender.sendMessage("handler calls=" + channel.handlerCalls() + " avg=" + channel.handlerAvgNanos() / 1000 + "us max=" + channel.handlerMaxNanos() / 1000 + "us (main thread)");
        channel.counters().snapshot().forEach((k, v) -> sender.sendMessage("  " + k + " = " + v));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        if (!sender.hasPermission("awre.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) { for (String s : SUBS) if (s.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(s); }
        else if (a.length == 2 && a[0].equalsIgnoreCase("sendbad")) { for (String k : FaultInjector.KINDS) if (k.startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(k); }
        else if (a.length == 2 && a[0].equalsIgnoreCase("debug")) { out.add("on"); out.add("off"); }
        return out;
    }
}
