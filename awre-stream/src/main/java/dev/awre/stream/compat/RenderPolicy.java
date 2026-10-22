package dev.awre.stream.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides whether AWRE draws terrain, from the {@code awre.render} setting and the mods that are loaded. Pure Java: the adapter only
 * supplies "is this mod id loaded?". Policy (docs/COMPAT_PLAN.md): AWRE is meant to WORK WITH Sodium, Iris, VulkanMod and Bobby, so
 * those never switch drawing off; they are only reported. Mods that draw the same distant terrain (Distant Horizons, Voxy) switch it
 * off in AUTO to avoid two layers.
 */
public final class RenderPolicy {
    private RenderPolicy() { }

    public enum Mode {
        AUTO, ON, OFF;
        public static Mode parse(String s) {
            if (s == null) return AUTO;
            return switch (s.strip().toLowerCase(Locale.ROOT)) { case "on", "true" -> ON; case "off", "false" -> OFF; default -> AUTO; };
        }
    }

    /** @param notes human-readable lines for the log, in order */
    public record Decision(boolean renderEnabled, List<String> notes) { }

    public static final Set<String> OVERLAPPING_LOD_MODS = Set.of("distanthorizons", "voxy");
    public static final List<String> TARGET_MODS = List.of("sodium", "iris", "vulkanmod", "bobby");

    public static Decision decide(Mode mode, Predicate<String> isLoaded) {
        List<String> notes = new ArrayList<>();
        String overlap = null;
        for (String id : OVERLAPPING_LOD_MODS.stream().sorted().toList()) if (isLoaded.test(id)) { overlap = id; break; }
        for (String id : TARGET_MODS) if (isLoaded.test(id)) notes.add("[AWRE] compat target loaded: " + id + " (drawing stays enabled; behaviour with it is NOT TESTED yet)");
        boolean enabled = switch (mode) { case OFF -> false; case ON -> true; case AUTO -> overlap == null; };
        if (overlap != null) notes.add("[AWRE] LOD mod '" + overlap + "' draws the same terrain; awre.render=" + mode.name().toLowerCase(Locale.ROOT) + " -> drawing " + (enabled ? "ENABLED (forced)" : "disabled"));
        else if (mode == Mode.OFF) notes.add("[AWRE] awre.render=off -> drawing disabled");
        return new Decision(enabled, List.copyOf(notes));
    }
}
