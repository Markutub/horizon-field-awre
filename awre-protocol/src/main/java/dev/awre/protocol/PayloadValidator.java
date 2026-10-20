package dev.awre.protocol;

/**
 * Decides whether the decoded bytes of a tile are acceptable content (called after the bounded inflate and the payload CRC).
 * Returning false makes the client answer INVALID without storing the tile. Must be cheap, side-effect free and must never throw
 * on hostile input (return false instead).
 */
@FunctionalInterface
public interface PayloadValidator {
    boolean accept(int tileX, int tileZ, int lod, long revision, byte[] decoded);

    /** G0 behaviour and the default: only the deterministic test tiles are valid. */
    PayloadValidator TEST_TILES = TestTile::verify;
}
