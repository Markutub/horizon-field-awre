import dev.awre.stream.SyntheticTerrain;
import dev.horizonfield.core.lod.*;
import dev.horizonfield.core.terrain.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * CPU REFERENCE FRAME (not the game): software z-buffer render of the real LOD plan using the same tile-local meshes the client builds
 * (TerrainMesher.meshLocalToFloor, positions made camera-relative in double like CameraRelative does). It checks GEOMETRY only: that the
 * plan + meshes cover the ground in front of a camera without see-through holes, at the origin, at negative and at very large coordinates.
 * It cannot show GPU/driver behaviour, fog, lighting, real biome colours or Sodium/Iris interaction: the in-game test is docs/MANUAL_RENDER_TEST.md.
 * Usage: java -cp <classes> ReferenceFrame.java [outDir]    Exit 1 if any view has an uncovered pixel in its near-ground band an interior hole, or a depth see-through sample.
 */
public class ReferenceFrame {
    static final int W = 640, H = 360;
    static final double FOV_V = Math.toRadians(70), NEAR = 0.3;

    record View(String name, int cx, int cz, double yaw) { }

    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : "reference-frames"); out.mkdirs();
        int bad = 0;
        View[] views = { new View("origin", 0, 0, 0.0), new View("origin-diagonal", 0, 0, 0.8), new View("negative", -3125, -3125, 2.4), new View("large", 62500, 62500, 4.0) };
        System.out.println("view | tris drawn | covered % | near-ground band uncovered px | interior holes | see-through (rendered farther than true ground, <=300 blocks) | lod tint share (L0..L6) %");
        for (View v : views) bad += render(v, out);
        if (bad > 0) { System.out.println("REFERENCE-FRAME FAIL: " + bad + " view(s) with see-through holes"); System.exit(1); }
        System.out.println("REFERENCE-FRAME PASS (geometry only; see docs/MANUAL_RENDER_TEST.md for the real frame)");
    }

    static int render(View v, File out) throws Exception {
        LodModel m = LodModel.defaults();
        double camX = v.cx() * 16 + 8.0, camZ = v.cz() * 16 + 8.0, radius = 700;
        TerrainStore store = new TerrainStore(m.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
        int cr = (int) (radius / 16) + 20;
        SyntheticTerrain.fill(store, v.cx() - cr, v.cz() - cr, v.cx() + cr, v.cz() + cr, 1234);
        var pal = SyntheticTerrain.palette();
        LodPlan plan = new LodPlanner(m).plan(camX, camZ, radius, null);
        double camY = SyntheticTerrain.heightAt((int) camX, (int) camZ, 1234) + 3;
        double[] zbuf = new double[W * H]; java.util.Arrays.fill(zbuf, Double.POSITIVE_INFINITY);
        int[] img = new int[W * H], lodOf = new int[W * H]; java.util.Arrays.fill(img, 0xFF8FB8E8); java.util.Arrays.fill(lodOf, -1);   // sky
        double f = (H / 2.0) / Math.tan(FOV_V / 2), cy = Math.cos(v.yaw()), sy = Math.sin(v.yaw());
        long tris = 0;
        for (LodKey k : plan.leaves()) {
            LodTile t = store.tile(k); if (t == null) continue;
            TerrainMesher.Mesh mesh = TerrainMesher.meshLocalToFloor(t, id -> id >= 0 && id < pal.size() ? pal.keyOf(id) : "minecraft:air", -64);
            double offX = mesh.originX - camX, offZ = mesh.originZ - camZ;          // double subtraction FIRST, then float-sized values
            int vc = mesh.vertexCount(); double[][] cam = new double[vc][3];
            for (int i = 0; i < vc; i++) {
                double x = mesh.positions[i * 3] + offX, y = mesh.positions[i * 3 + 1] - camY, z = mesh.positions[i * 3 + 2] + offZ;
                cam[i][0] = x * cy - z * sy; cam[i][1] = y; cam[i][2] = x * sy + z * cy;   // yaw rotation; +z = forward
            }
            for (int ti = 0; ti < mesh.triangleCount(); ti++) {
                int i0 = mesh.indices[ti * 3], i1 = mesh.indices[ti * 3 + 1], i2 = mesh.indices[ti * 3 + 2];
                double shade = switch (TerrainMesher.Face.values()[mesh.faces[i0]]) { case UP -> 1.0; case SKIRT -> 0.5; default -> 0.75; };
                int col = mesh.colors[i0];
                tris += raster(new double[][]{cam[i0], cam[i1], cam[i2]}, shade(col, shade), k.lod(), f, zbuf, img, lodOf);
            }
        }
        // metrics
        int covered = 0, bandHoles = 0, bandFrom = (int) (H * 0.65);                // bottom 35% of the frame looks at the nearest ground
        long[] lodPx = new long[8];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            boolean c = lodOf[y * W + x] >= 0; if (c) { covered++; lodPx[lodOf[y * W + x]]++; } else if (y >= bandFrom) bandHoles++;
        }
        // interior holes: an uncovered pixel with terrain both above and below it in its column = a see-through crack, not the skyline
        int interior = 0;
        for (int x = 0; x < W; x++) {
            int firstCovered = -1, lastCovered = -1;
            for (int y = 0; y < H; y++) if (lodOf[y * W + x] >= 0) { if (firstCovered < 0) firstCovered = y; lastCovered = y; }
            for (int y = firstCovered + 1; firstCovered >= 0 && y < lastCovered; y++) if (lodOf[y * W + x] < 0) interior++;
        }
        // see-through: the rendered surface is much FARTHER than the true ground along the same ray (a missing tile shows farther terrain, not sky).
        // Ground truth = independent 1-block ray march over the analytic synthetic heightfield (NOT the tile data under test), within 300 blocks.
        int seeThrough = 0, sampled = 0;
        for (int y = 0; y < H; y += 3) for (int x = 0; x < W; x += 3) {
            double dx = (x + 0.5 - W / 2.0) / f, dy = -(y + 0.5 - H / 2.0) / f, zTrue = Double.NaN;
            double wx = dx * cy + sy, wz = -dx * sy + cy;                    // world direction (per unit camera z)
            for (double t = 1; t <= 300; t += 1) {
                double px = camX + wx * t, pz = camZ + wz * t, py = camY + dy * t;
                if (py <= SyntheticTerrain.heightAt((int) Math.floor(px), (int) Math.floor(pz), 1234) + 1) { zTrue = t; break; }
            }
            if (Double.isNaN(zTrue)) continue;                                // ray leaves the 300-block window without hitting ground
            sampled++;
            double zr = zbuf[y * W + x];
            if (zr > zTrue + 6 + 0.15 * zTrue) seeThrough++;                  // beyond LOD averaging error: geometry is missing here
        }
        StringBuilder share = new StringBuilder();
        for (int l = 0; l < 7; l++) share.append(String.format("%.0f ", 100.0 * lodPx[l] / Math.max(1, covered)));
        System.out.printf("%s | %,d | %.1f | %d | interior holes %d | see-through %d/%d | %s%n", v.name(), tris, 100.0 * covered / (W * H), bandHoles, interior, seeThrough, sampled, share.toString().trim());
        BufferedImage bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); bi.setRGB(0, 0, W, H, img, 0, W);
        javax.imageio.ImageIO.write(bi, "png", new File(out, "frame-" + v.name() + ".png"));
        // Tolerance from the measured baseline (2026-10-22): clean views <= 1 sample (0.007%), a 20%-tiles-missing mutation >= 1.3%. 0.1% separates them.
        return (bandHoles > 0 || interior > 0 || seeThrough * 1000L > sampled) ? 1 : 0;
    }

    static int shade(int argb, double s) {
        int r = (int) (((argb >> 16) & 255) * s), g = (int) (((argb >> 8) & 255) * s), b = (int) ((argb & 255) * s);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Near-plane clip + perspective z-buffer fill. Returns 1 when the triangle produced any fragment work. */
    static int raster(double[][] tri, int color, int lod, double f, double[] zbuf, int[] img, int[] lodOf) {
        java.util.List<double[]> poly = new java.util.ArrayList<>(); 
        for (int i = 0; i < 3; i++) {
            double[] p = tri[i], q = tri[(i + 1) % 3];
            boolean pin = p[2] >= NEAR, qin = q[2] >= NEAR;
            if (pin) poly.add(p);
            if (pin != qin) { double t = (NEAR - p[2]) / (q[2] - p[2]); poly.add(new double[]{p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1]), NEAR}); }
        }
        if (poly.size() < 3) return 0;
        for (int i = 1; i + 1 < poly.size(); i++) fill(poly.get(0), poly.get(i), poly.get(i + 1), color, lod, f, zbuf, img, lodOf);
        return 1;
    }

    static void fill(double[] a, double[] b, double[] c, int color, int lod, double f, double[] zbuf, int[] img, int[] lodOf) {
        double[] ax = proj(a, f), bx = proj(b, f), cx = proj(c, f);
        double minX = Math.min(ax[0], Math.min(bx[0], cx[0])), maxX = Math.max(ax[0], Math.max(bx[0], cx[0]));
        double minY = Math.min(ax[1], Math.min(bx[1], cx[1])), maxY = Math.max(ax[1], Math.max(bx[1], cx[1]));
        int x0 = Math.max(0, (int) Math.floor(minX)), x1 = Math.min(W - 1, (int) Math.ceil(maxX));
        int y0 = Math.max(0, (int) Math.floor(minY)), y1 = Math.min(H - 1, (int) Math.ceil(maxY));
        double den = (bx[1] - cx[1]) * (ax[0] - cx[0]) + (cx[0] - bx[0]) * (ax[1] - cx[1]);
        if (Math.abs(den) < 1e-12) return;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            double px = x + 0.5, py = y + 0.5;
            double l1 = ((bx[1] - cx[1]) * (px - cx[0]) + (cx[0] - bx[0]) * (py - cx[1])) / den;
            double l2 = ((cx[1] - ax[1]) * (px - cx[0]) + (ax[0] - cx[0]) * (py - cx[1])) / den;
            double l3 = 1 - l1 - l2, e = -1e-6;
            if (l1 < e || l2 < e || l3 < e) continue;
            double z = 1 / (l1 / a[2] + l2 / b[2] + l3 / c[2]);
            int idx = y * W + x;
            if (z < zbuf[idx]) { zbuf[idx] = z; img[idx] = color; lodOf[idx] = Math.min(7, lod); }
        }
    }

    static double[] proj(double[] p, double f) { return new double[]{ W / 2.0 + f * p[0] / p[2], H / 2.0 - f * p[1] / p[2] }; }
}
