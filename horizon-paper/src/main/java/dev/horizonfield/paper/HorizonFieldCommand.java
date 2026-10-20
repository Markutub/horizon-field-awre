package dev.horizonfield.paper;

import dev.horizonfield.core.HeightPyramid;
import dev.horizonfield.core.Metrics;
import dev.horizonfield.core.ShellMesher;
import dev.horizonfield.core.ShellPrism;
import dev.horizonfield.core.VisibilityParams;
import dev.horizonfield.core.VisibilityResult;
import dev.horizonfield.core.VisibilitySweep;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** /horizon status | probe info | probe ring <dist> [viewRange] [seconds] | probe clear | sweep [maxRadius] | flush */
final class HorizonFieldCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final HorizonConfig cfg;
    private final CaptureService capture;
    private final ProbeService probes;
    private final Metrics metrics;

    HorizonFieldCommand(JavaPlugin plugin, HorizonConfig cfg, CaptureService capture, ProbeService probes, Metrics metrics) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.capture = capture;
        this.probes = probes;
        this.metrics = metrics;
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Component.text(msg, NamedTextColor.GRAY)); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { say(sender, "Usage: /" + label + " status|probe|sweep|flush"); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "flush" -> { capture.flushAll(); say(sender, "flushed"); }
            case "probe" -> probe(sender, args);
            case "sweep" -> sweep(sender, args);
            default -> say(sender, "Unknown subcommand");
        }
        return true;
    }

    private void status(CommandSender s) {
        say(s, "HorizonField (scaffold: observation + diagnostics only; it does NOT render distant terrain)");
        say(s, "capture queue=" + capture.captureQueueSize() + " analysis queue=" + capture.analysisQueueSize());
        metrics.snapshot().forEach((k, v) -> say(s, "  " + k + " = " + v));
        if (s instanceof Player p) {
            WorldState st = capture.peek(p.getWorld());
            if (st != null) {
                StringBuilder sb = new StringBuilder("pyramid nodes by level:");
                for (int l = 0; l <= st.pyramid.maxLevel(); l++) sb.append(' ').append(st.pyramid.size(l));
                say(s, sb.toString());
                say(s, "world palette=" + st.palette.size() + " known chunks (dedup)=" + st.deduper.size());
            }
        }
    }

    private void probe(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { say(sender, "players only"); return; }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (sub) {
            case "info" -> {
                say(p, "client view distance (chunks) = " + p.getClientViewDistance());
                say(p, "player view/send/simulation = " + p.getViewDistance() + "/" + p.getSendViewDistance() + "/" + p.getSimulationDistance());
                say(p, "world view/send/simulation = " + p.getWorld().getViewDistance() + "/" + p.getWorld().getSendViewDistance()
                        + "/" + p.getWorld().getSimulationDistance());
                say(p, "Record these with your experiment notes (docs/EXPERIMENTS.md).");
            }
            case "ring" -> {
                double dist = args.length > 2 ? parse(args[2], 128) : 128;
                float vr = (float) (args.length > 3 ? parse(args[3], 4) : 4);
                int sec = (int) Math.min(cfg.probeMaxSeconds(), args.length > 4 ? parse(args[4], 30) : 30);
                ProbeService.Result r = probes.spawnRing(p, dist, vr, cfg.probeMaxPillars(), sec, Material.RED_CONCRETE);
                say(p, "spawned " + r.spawned() + " pillars at " + dist + " blocks (viewRange=" + vr + "), skipped(unloaded)=" + r.skippedUnloaded()
                        + ". Auto-removed in " + sec + "s.");
            }
            case "clear" -> { probes.clear(); say(p, "cleared"); }
            default -> say(p, "probe info|ring <dist> [viewRange] [seconds]|clear");
        }
    }

    private static double parse(String s, double def) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    private void sweep(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { say(sender, "players only"); return; }
        WorldState st = capture.peek(p.getWorld());
        if (st == null) { say(p, "no data for this world yet"); return; }
        int maxR = (int) (args.length > 1 ? parse(args[1], cfg.sweepDefaultMaxRadius()) : cfg.sweepDefaultMaxRadius());
        Location eye = p.getEyeLocation();
        double minR = Math.max(32, p.getClientViewDistance() * 16.0);
        if (maxR <= minR) { say(p, "maxRadius must exceed " + (int) minR + " (client view distance * 16)"); return; }
        VisibilityParams q = new VisibilityParams(eye.getX(), eye.getY(), eye.getZ(), minR, maxR, cfg.sweepAzimuthBins(), cfg.sweepSlackBlocks());
        HeightPyramid pyramid = st.pyramid;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            VisibilityResult r = VisibilitySweep.run(pyramid, q);
            List<ShellPrism> prisms = ShellMesher.mesh(r.cells(), q, 4096, 4);
            int visibleChunks = r.toChunkSet().size();
            List<String> lines = new ArrayList<>();
            lines.add(String.format(Locale.ROOT, "sweep r=[%.0f,%d] bins=%d: cells=%d visibleChunks=%d probes=%d unknownProbes=%d prisms=%d time=%.2fms",
                    minR, maxR, q.azimuthBins(), r.cells().size(), visibleChunks, r.probes(), r.unknownProbes(), prisms.size(), r.nanos() / 1e6));
            lines.add("Only observed chunks are known; unknownProbes>0 means the cache has not seen that terrain (expected on a fresh install).");
            plugin.getServer().getScheduler().runTask(plugin, () -> lines.forEach(l -> say(p, l)));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "probe", "sweep", "flush");
        if (args.length == 2 && args[0].equalsIgnoreCase("probe")) return List.of("info", "ring", "clear");
        return List.of();
    }
}
