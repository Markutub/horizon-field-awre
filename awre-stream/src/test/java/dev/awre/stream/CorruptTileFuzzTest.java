package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import dev.horizonfield.core.terrain.TerrainMesher;
import dev.horizonfield.core.terrain.TerrainStore;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Deterministic mutation fuzz of the tile wire format. Guarantees checked: decoding hostile bytes either succeeds or throws
 * IOException/RuntimeException (what {@link TerrainPayloads#TERRAIN} catches), never an Error and never a hang; and any mutated tile that
 * DOES decode can still be meshed, so corrupt-but-decodable data cannot crash the render thread's mesher.
 */
class CorruptTileFuzzTest {
    private static final long SEED = 2026;

    private static byte[] mutate(byte[] src, Random r) {
        byte[] b = src.clone();
        switch (r.nextInt(5)) {
            case 0 -> { for (int i = 0, n = 1 + r.nextInt(8); i < n; i++) b[r.nextInt(b.length)] ^= (byte) (1 << r.nextInt(8)); }   // bit flips
            case 1 -> b = java.util.Arrays.copyOf(b, r.nextInt(b.length));                                                       // truncate
            case 2 -> b = java.util.Arrays.copyOf(b, b.length + 1 + r.nextInt(64));                                              // extend with zeros
            case 3 -> { r.nextBytes(b); }                                                                                        // pure noise
            default -> { int off = r.nextInt(b.length); int len = Math.min(b.length - off, 1 + r.nextInt(32)); for (int i = 0; i < len; i++) b[off + i] = (byte) (r.nextBoolean() ? 0xFF : 0x00); }
        }
        return b;
    }

    @Test void mutatedTilesAreRejectedOrSafeAndNeverCrashTheMesher() throws Exception {
        TerrainStore store = new TerrainStore(6, TerrainStore.Reduce.MEAN, 20_000);
        SyntheticTerrain.fill(store, 0, 0, 7, 7, SEED);
        var pal = SyntheticTerrain.palette();
        LodKey[] keys = { new LodKey(0, 0, 0), new LodKey(0, 3, 4), new LodKey(1, 1, 1), new LodKey(2, 0, 0) };
        Random r = new Random(SEED);
        int decoded = 0, rejected = 0;
        long start = System.nanoTime();
        for (LodKey k : keys) {
            LodTile t = store.tile(k);
            assertNotNull(t, "premise: tile exists " + k);
            for (byte[] original : new byte[][] { LodTileCodec.encode(t), LodTileCodec.encodeV2(t) }) {
                for (int i = 0; i < 1500; i++) {
                    byte[] bad = mutate(original, r);
                    LodTile out;
                    try { out = LodTileCodec.decode(k, bad); }
                    catch (IOException | RuntimeException expected) { rejected++; continue; }
                    decoded++;
                    TerrainMesher.Mesh m = TerrainMesher.meshLocal(out, pal::keyOf, 8);   // must not throw on any decodable tile
                    assertTrue(m.triangleCount() >= 0);
                }
            }
        }
        assertTrue(rejected > 1000, "premise: the fuzz actually produced rejections (" + rejected + ")");
        assertTrue(decoded > 0, "premise: some mutations stay decodable, so the mesher path was exercised");
        assertTrue((System.nanoTime() - start) / 1e9 < 60, "decoding hostile input must not be slow");
    }

    @Test void theWireValidatorNeverThrowsOnHostileBytes() {
        Random r = new Random(SEED + 1);
        for (int i = 0; i < 3000; i++) {
            byte[] junk = new byte[r.nextInt(2048)];
            r.nextBytes(junk);
            TerrainPayloads.TERRAIN.accept(r.nextInt(1000) - 500, r.nextInt(1000) - 500, r.nextInt(7), r.nextLong(), junk);   // returns true/false, no exception
        }
        assertFalse(TerrainPayloads.TERRAIN.accept(0, 0, 0, 1, new byte[0]));
        assertFalse(TerrainPayloads.TERRAIN.accept(0, 0, 0, 1, new byte[] { 2 }));
    }
}
