package dev.awre.stream;

import dev.horizonfield.core.ColumnTile;
import dev.horizonfield.core.Confidence;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.terrain.TerrainStore;

/**
 * Deterministic made-up terrain for exercising the pipeline without a real capture. It is labelled PREDICTED on purpose:
 * generated data must never be presented as observed. Uses StrictMath so every JVM produces identical tiles.
 */
public final class SyntheticTerrain {
    private SyntheticTerrain() { }

    public static int heightAt(int x, int z, long seed) {
        double p1 = (seed & 0xFF) * 0.0245, p2 = ((seed >>> 8) & 0xFF) * 0.0245, p3 = ((seed >>> 16) & 0xFF) * 0.0245;
        double h = 64
                + 28 * StrictMath.sin(x / 310.0 + p1) * StrictMath.cos(z / 270.0 + p2)
                + 14 * StrictMath.sin((x + z) / 97.0 + p3)
                + 6 * StrictMath.sin(x / 23.0) * StrictMath.sin(z / 19.0);
        return (int) Math.max(1, Math.min(250, Math.round(h)));
    }

    /** The palette that {@link #materialFor} indexes: 0 air, 1 grass, 2 sand, 3 stone, 4 snow. */
    public static MaterialPalette palette() {
        MaterialPalette p = new MaterialPalette();
        p.restore(1, "minecraft:grass_block"); p.restore(2, "minecraft:sand"); p.restore(3, "minecraft:stone"); p.restore(4, "minecraft:snow_block");
        return p;
    }

    public static int materialFor(int height) { return height < 60 ? 2 : height < 75 ? 1 : height < 100 ? 3 : 4; }

    public static ColumnTile chunk(int cx, int cz, long seed) {
        short[] top = new short[256], mat = new short[256];
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int h = heightAt(cx * 16 + lx, cz * 16 + lz, seed);
            top[lz * 16 + lx] = (short) h; mat[lz * 16 + lx] = (short) materialFor(h);
        }
        return new ColumnTile(cx, cz, top, mat, Confidence.PREDICTED, 0);
    }

    /** Fill an inclusive chunk rectangle. Memory is about 1.3 KB per chunk: callers cap the rectangle. */
    public static int fill(TerrainStore s, int cx0, int cz0, int cx1, int cz1, long seed) {
        if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > 40_000) throw new IllegalArgumentException("too many chunks");
        int n = 0;
        for (int x = cx0; x <= cx1; x++) for (int z = cz0; z <= cz1; z++) { s.put(chunk(x, z, seed)); n++; }
        return n;
    }
}
