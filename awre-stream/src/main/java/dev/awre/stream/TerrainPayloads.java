package dev.awre.stream;

import dev.awre.protocol.PayloadValidator;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTileCodec;
import java.io.IOException;

/** Client-side content checks for terrain tiles. */
public final class TerrainPayloads {
    private TerrainPayloads() { }

    /** Accepts exactly the payloads {@link LodTileCodec} can decode (never throws). */
    public static final PayloadValidator TERRAIN = (x, z, lod, rev, d) -> {
        try { LodTileCodec.decode(new LodKey(lod, x, z), d); return true; } catch (IOException | RuntimeException e) { return false; }
    };

    /** G0/G1 test tiles or terrain tiles (what the Fabric mod installs). */
    public static final PayloadValidator ANY = (x, z, lod, rev, d) ->
            PayloadValidator.TEST_TILES.accept(x, z, lod, rev, d) || TERRAIN.accept(x, z, lod, rev, d);
}
