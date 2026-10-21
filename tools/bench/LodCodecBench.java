import dev.awre.stream.SyntheticTerrain;
import dev.horizonfield.core.lod.LodKey;
import dev.horizonfield.core.terrain.LodTile;
import dev.horizonfield.core.terrain.LodTileCodec;
import dev.horizonfield.core.terrain.TerrainStore;
import java.util.zip.Deflater;

/**
 * Raw and deflated size of LodTileCodec format 1 vs format 2 on SYNTHETIC (smooth, made-up) terrain: real terrain is rougher, so
 * treat the ratios as a best case until this is run on a real .hfl. Usage: tools/bench/run-lodcodec.sh
 */
public final class LodCodecBench {
    static int deflated(byte[] b, int level) {
        Deflater d = new Deflater(level);
        d.setInput(b); d.finish();
        byte[] out = new byte[b.length + 64];
        int n = 0;
        while (!d.finished()) n += d.deflate(out);
        d.end();
        return n;
    }

    public static void main(String[] a) {
        TerrainStore s = new TerrainStore(4, TerrainStore.Reduce.MEAN, 1 << 20);
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) s.put(SyntheticTerrain.chunk(x, z, 12345L));
        System.out.println("level tiles  v1raw v2raw(avg) | v1defl v2defl (BEST_SPEED)  | v1defl v2defl (default)  | v2/v1 defl");
        for (int lod = 0; lod <= 4; lod++) {
            int side = 64 >> lod, tiles = 0;
            long r1 = 0, r2 = 0, d1s = 0, d2s = 0, d1d = 0, d2d = 0;
            for (int x = 0; x < side; x++) for (int z = 0; z < side; z++) {
                LodTile t = s.tile(new LodKey(lod, x, z));
                if (t == null) continue;
                byte[] b1 = LodTileCodec.encode(t), b2 = LodTileCodec.encodeV2(t);
                tiles++; r1 += b1.length; r2 += b2.length;
                d1s += deflated(b1, Deflater.BEST_SPEED); d2s += deflated(b2, Deflater.BEST_SPEED);
                d1d += deflated(b1, Deflater.DEFAULT_COMPRESSION); d2d += deflated(b2, Deflater.DEFAULT_COMPRESSION);
            }
            if (tiles == 0) continue;
            System.out.printf("%d  %5d  %5d %6.0f | %6.0f %6.0f | %6.0f %6.0f | %.2f%n", lod, tiles, r1 / tiles, (double) r2 / tiles,
                    (double) d1s / tiles, (double) d2s / tiles, (double) d1d / tiles, (double) d2d / tiles, (double) d2d / d1d);
        }
    }
}
