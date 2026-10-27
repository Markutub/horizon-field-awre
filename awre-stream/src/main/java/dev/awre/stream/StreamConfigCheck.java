package dev.awre.stream;

import java.util.List;

/**
 * Explains what a stream configuration will ACTUALLY do, in plain words, so "enabled: true but nothing shows up" cannot stay silent. Used for the
 * server log at startup and for /awre status. Pure Java. The facts it encodes come from config.yml's own documentation: with source "synthetic" the
 * terrain is made up and {@code synthetic-chunk-radius: 0} means none at all; "captured" only knows chunks the server has already loaded.
 */
public final class StreamConfigCheck {
    public enum Level { WARN, NOTE }
    public record Finding(Level level, String text) { }

    private StreamConfigCheck() { }

    public static List<Finding> findings(boolean streamEnabled, boolean captured, int syntheticChunkRadius) {
        if (!streamEnabled) {
            return List.of(new Finding(Level.WARN, "stream.enabled is false: terrain is not streamed automatically, so clients only receive the single handshake test tile. "
                    + "Set stream.enabled: true (and restart), or start one player with /awre stream on."));
        }
        if (captured) {
            return List.of(new Finding(Level.NOTE, "stream.source is captured: only chunks the server has already loaded are recorded and streamed (never generated for you). "
                    + "Areas no player has been near stay empty until they are loaded once; stream.persist keeps what was recorded across restarts."));
        }
        if (syntheticChunkRadius <= 0) {
            return List.of(new Finding(Level.WARN, "stream.source is synthetic and synthetic-chunk-radius is 0, which means NO terrain at all: only the single handshake test tile is ever sent. "
                    + "To show your real world set stream.source: captured (restart, then let the server load the area). For made-up test hills set synthetic-chunk-radius above 0."));
        }
        return List.of(new Finding(Level.WARN, "stream.source is synthetic: the terrain is made-up hills around 0,0 (" + (2 * syntheticChunkRadius + 1) + "x" + (2 * syntheticChunkRadius + 1)
                + " chunks) and will NOT match your real world. Set stream.source: captured to stream the world's own chunks."));
    }
}
