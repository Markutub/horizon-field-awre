package dev.awre.stream.compat;

import java.util.Locale;

/**
 * Which render distance AWRE treats as "what vanilla draws" (tiles fully inside it are not drawn by AWRE). Pure Java.
 *
 * <p>EFFECTIVE (default, since the G2 audit): min(client option, server view distance) = the square vanilla really draws, so AWRE fills the
 * rest. CLIENT: the raw client option; for mods that draw real chunks out to the client's setting even where the server sends none
 * (Bobby is the expected case; manual check 35 decides whether it is needed). With CLIENT and no such mod, a ring between the server's view
 * distance and the client option shows NEITHER vanilla nor AWRE terrain, which is why it is not the default.
 */
public final class VanillaRadius {
    private VanillaRadius() { }

    public enum Mode {
        EFFECTIVE, CLIENT;
        /** Unknown or null = EFFECTIVE. */
        public static Mode parse(String s) {
            return s != null && s.strip().toLowerCase(Locale.ROOT).equals("client") ? CLIENT : EFFECTIVE;
        }
    }

    /** @return the radius in chunks (never negative) */
    public static int chunks(Mode mode, int clientOptionChunks, int effectiveChunks) {
        return Math.max(0, mode == Mode.CLIENT ? clientOptionChunks : effectiveChunks);
    }
}
