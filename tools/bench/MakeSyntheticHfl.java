package dev.horizonfield.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a SYNTHETIC .hfl (noise terrain, radius 32 chunks) to test the real-data pipeline. Usage: MakeSyntheticHfl <out.hfl> */
public class MakeSyntheticHfl {
    public static void main(String[] a) throws Exception {
        Path f = Path.of(a[0]); Files.deleteIfExists(f);
        MaterialPalette pal = new MaterialPalette();
        for (String k : new String[]{"minecraft:andesite", "minecraft:grass_block", "minecraft:sand", "minecraft:stone", "minecraft:snow_block", "minecraft:podzol"}) pal.idOf(k); // ids 1..6
        try (TileLog log = TileLog.open(f, "synthetic|selftest", pal, t -> { }).log()) {
            for (int cz = -32; cz <= 32; cz++) for (int cx = -32; cx <= 32; cx++) {
                short[] top = new short[256], m = new short[256];
                for (int i = 0; i < 256; i++) { int bx = cx * 16 + (i & 15), bz = cz * 16 + (i >> 4); top[i] = (short) CodecBench.height(bx, bz, 120); m[i] = CodecBench.material(top[i], bx, bz); }
                log.append(new ColumnTile(cx, cz, top, m, Confidence.OBSERVED, 0L));
            }
        }
        System.out.println("wrote " + f + " (" + Files.size(f) + " bytes)");
    }
}
