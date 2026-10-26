package dev.awre.stream;

/**
 * Which worlds the terrain pipeline may capture and stream. Pure Java so the decision is tested without Paper.
 *
 * <p>The representation is ONE surface per 1x1 column: the highest non-air block. In a world with a ceiling (the Nether) that block is
 * the bedrock roof, so capturing it would stream a flat roof at the top of the world as if it were the terrain, and the client would
 * draw it. Skipping such worlds is the honest behaviour until a representation with more than one surface per column exists.
 * Only the vanilla Nether is recognised by environment name; a datapack dimension with a ceiling reports CUSTOM and is NOT detected
 * (Paper's {@code World#hasCeiling} would cover it; its availability is not verified here).
 */
public final class WorldEligibility {
    private WorldEligibility() { }

    /** @param environmentName {@code World.Environment#name()}: NORMAL, NETHER, THE_END or CUSTOM; null/unknown = not a ceiling */
    public static boolean hasCeiling(String environmentName) { return "NETHER".equalsIgnoreCase(environmentName); }

    /** @return true when the world must not be captured or streamed */
    public static boolean skipsStreaming(String environmentName, boolean skipCeilingWorlds) { return skipCeilingWorlds && hasCeiling(environmentName); }
}
