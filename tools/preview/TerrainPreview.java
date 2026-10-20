import dev.horizonfield.core.lod.*;
import dev.horizonfield.core.terrain.*;
import dev.awre.stream.SyntheticTerrain;
import java.awt.image.BufferedImage;
import java.io.File;

/** Top-down picture of the LOD plan over synthetic terrain: hillshaded block colours, optional level tint and tile outlines. Sandbox tool, not shipped. */
public class TerrainPreview {
    static final int[] LEVEL_TINT = {0xFFFFFF, 0xFF8080, 0xFFC060, 0xFFFF60, 0x70E070, 0x60C0FF, 0xA080FF};

    public static void main(String[] a) throws Exception {
        double px = a.length > 0 ? Double.parseDouble(a[0]) : 0, pz = a.length > 1 ? Double.parseDouble(a[1]) : 0;
        double radius = a.length > 2 ? Double.parseDouble(a[2]) : 700;
        String out = a.length > 3 ? a[3] : "preview";
        int size = (int) (radius * 2);
        LodModel m = LodModel.defaults();
        TerrainStore store = new TerrainStore(m.maxLod(), TerrainStore.Reduce.MEAN, 20000);
        int cr = (int) (radius / 16) + 20;
        SyntheticTerrain.fill(store, (int) (px / 16) - cr, (int) (pz / 16) - cr, (int) (px / 16) + cr, (int) (pz / 16) + cr, 1234);
        var pal = SyntheticTerrain.palette();
        LodPlan plan = new LodPlanner(m).plan(px, pz, radius, null);
        for (int pass = 0; pass < 2; pass++) {
            boolean tint = pass == 1;
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setRGB(x, y, 0xFF101018);
            for (LodKey k : plan.leaves()) {
                LodTile t = store.tile(k);
                if (t == null) continue;
                int cell = 1 << k.lod();
                double ox = k.tx() * 16.0 * cell - (px - radius), oz = k.tz() * 16.0 * cell - (pz - radius);
                for (int sz = 0; sz < 16; sz++) for (int sx = 0; sx < 16; sx++) {
                    int h = t.topAt(sx, sz);
                    if (h == LodTile.NO_DATA) continue;
                    int hx = sx > 0 ? t.topAt(sx - 1, sz) : h, hz = sz > 0 ? t.topAt(sx, sz - 1) : h;
                    if (hx == LodTile.NO_DATA) hx = h; if (hz == LodTile.NO_DATA) hz = h;
                    double shade = Math.max(0.55, Math.min(1.35, 1.0 + ((h - hx) + (h - hz)) * 0.9 / Math.max(1, cell)));
                    int c = BlockColors.argb(pal.keyOf(t.materialAt(sx, sz)));
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    if (tint) { int tc = LEVEL_TINT[Math.min(k.lod(), 6)]; r = (r * 55 + ((tc >> 16) & 255) * 45) / 100; g = (g * 55 + ((tc >> 8) & 255) * 45) / 100; b = (b * 55 + (tc & 255) * 45) / 100; }
                    int rgb = 0xFF000000 | clamp(r * shade) << 16 | clamp(g * shade) << 8 | clamp(b * shade);
                    for (int dz = 0; dz < cell; dz++) for (int dx = 0; dx < cell; dx++) {
                        int X = (int) (ox + sx * cell + dx), Z = (int) (oz + sz * cell + dz);
                        if (X >= 0 && Z >= 0 && X < size && Z < size) img.setRGB(X, Z, rgb);
                    }
                }
                if (tint) {   // tile outline
                    int w = 16 * cell;
                    for (int i = 0; i < w; i++) for (int[] e : new int[][]{{i, 0}, {i, w - 1}, {0, i}, {w - 1, i}}) {
                        int X = (int) ox + e[0], Z = (int) oz + e[1];
                        if (X >= 0 && Z >= 0 && X < size && Z < size) img.setRGB(X, Z, 0xFF000000);
                    }
                }
            }
            javax.imageio.ImageIO.write(img, "png", new File(out + (tint ? "-levels.png" : "-terrain.png")));
        }
        int[] c = plan.countByLevel(m.maxLod());
        System.out.println("leaves=" + plan.leaves().size() + " per level " + java.util.Arrays.toString(c));
    }

    static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }
}
