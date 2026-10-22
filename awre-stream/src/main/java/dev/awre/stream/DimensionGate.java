package dev.awre.stream;

import dev.awre.protocol.TileKey;
import java.util.function.Predicate;

/**
 * Decides which dimension's tiles may be drawn. Pure Java. The client knows its current dimension from the level it is in; that is
 * authoritative. Only while that is unknown (null) does the gate fall back to the dimension of the most recently received tile.
 * Using only the last received tile is wrong right after a dimension change: the old dimension's tiles would be drawn (and
 * re-uploaded) in the new level until the first new tile arrives.
 */
public final class DimensionGate {
    private DimensionGate() { }

    /** @param currentDimension e.g. "minecraft:the_nether" or null if unknown; @param lastTile fallback, may be null */
    public static Predicate<TileKey> filter(String currentDimension, TileKey lastTile) {
        if (currentDimension != null) return k -> k.dimension().equals(currentDimension);
        if (lastTile != null) return k -> k.dimension().equals(lastTile.dimension());
        return null;                                       // nothing known: no filtering (ClientHorizonView treats null as "all")
    }

    /**
     * Extracts the dimension id from {@code ResourceKey#toString()}, which looks like
     * {@code ResourceKey[minecraft:dimension / minecraft:overworld]}. Parsing text avoids depending on the accessor name
     * ({@code location()} vs {@code identifier()}) that changed around 1.21.11. Returns null if the text does not match.
     */
    public static String fromResourceKeyString(String s) {
        if (s == null) return null;
        int slash = s.lastIndexOf(" / "), end = s.lastIndexOf(']');
        if (slash < 0 || end <= slash + 3) return null;
        String id = s.substring(slash + 3, end).trim();
        return id.isEmpty() || id.indexOf(' ') >= 0 ? null : id;
    }
}
