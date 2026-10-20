package dev.horizonfield.paper;

import dev.horizonfield.core.HeightPyramid;
import dev.horizonfield.core.MaterialPalette;
import dev.horizonfield.core.TerrainAnalyzer;
import dev.horizonfield.core.TileDeduper;
import dev.horizonfield.core.TileLog;
import java.io.IOException;
import java.nio.file.Path;
import org.bukkit.World;

/** Per-world cache: height pyramid + persistent tile log. Created lazily on first use. */
final class WorldState implements AutoCloseable {
    final String worldName;
    final int minY;
    final int maxY;
    /** One palette per world/log: palette ids are persisted inside this world's log. */
    final MaterialPalette palette;
    final TerrainAnalyzer analyzer;
    final TileDeduper deduper;
    final HeightPyramid pyramid;
    final TileLog log;
    final TileLog.LoadReport loadReport;

    private WorldState(String worldName, int minY, int maxY, MaterialPalette palette, TileDeduper deduper,
                       HeightPyramid pyramid, TileLog log, TileLog.LoadReport report) {
        this.worldName = worldName;
        this.minY = minY;
        this.maxY = maxY;
        this.palette = palette;
        this.analyzer = new TerrainAnalyzer(palette);
        this.deduper = deduper;
        this.pyramid = pyramid;
        this.log = log;
        this.loadReport = report;
    }

    static String fingerprint(World w) {
        String gen = w.getGenerator() == null ? "vanilla" : w.getGenerator().getClass().getName();
        return "hf|" + w.getUID() + "|" + w.getSeed() + "|" + w.getMinHeight() + ":" + w.getMaxHeight() + "|" + gen;
    }

    static WorldState open(World w, Path cacheDir, int pyramidMaxLevel) throws IOException {
        HeightPyramid pyramid = new HeightPyramid(pyramidMaxLevel);
        MaterialPalette palette = new MaterialPalette();
        TileDeduper deduper = new TileDeduper();
        TileLog.Opened o = TileLog.open(cacheDir.resolve(w.getUID() + ".hfl"), fingerprint(w), palette, t -> {
            pyramid.put(t);
            deduper.seed(t);
        });
        return new WorldState(w.getName(), w.getMinHeight(), w.getMaxHeight() - 1, palette, deduper, pyramid, o.log(), o.report());
    }

    @Override
    public void close() throws IOException { log.close(); }
}
