package dev.horizonfield.core;

/** Converts a {@link ChunkView} into a {@link ColumnTile}. Pure and thread-safe: safe to run on worker threads. */
public final class TerrainAnalyzer {
    private final MaterialPalette palette;

    public TerrainAnalyzer(MaterialPalette palette) { this.palette = palette; }

    public ColumnTile analyze(ChunkView view, Confidence confidence, long nowMillis) {
        short[] top = new short[ColumnTile.AREA];
        short[] mat = new short[ColumnTile.AREA];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int y = view.highestBlockY(lx, lz);
                int i = lz * 16 + lx;
                if (y < view.minY()) {
                    top[i] = (short) (view.minY() - 1);
                    mat[i] = (short) MaterialPalette.AIR;
                } else {
                    top[i] = (short) y;
                    mat[i] = (short) palette.idOf(view.blockKey(lx, y, lz));
                }
            }
        }
        return new ColumnTile(view.chunkX(), view.chunkZ(), top, mat, confidence, nowMillis);
    }
}
