import dev.awre.protocol.TileKey;
import dev.awre.stream.RevealAnimator;
import dev.awre.stream.SyntheticTerrain;
import dev.horizonfield.core.lod.*;
import dev.horizonfield.core.terrain.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * CPU PREVIEW of the terrain reveal effect (NOT the game, NOT the GLSL): a software z-buffer renderer that applies, per vertex and in
 * float like the shader would, the same {@link RevealAnimator} functions (tau / lift / grey / greyMix) the vertex and fragment shaders mirror,
 * driven by the real stateful {@code RevealAnimator#forDraw}. Tiles are the real LOD plan with the real tile-local meshes, positioned
 * camera-relative in double like the renderer does. Use it to see and tune the effect (-Dawre.reveal.speed=... etc. are honoured) and to
 * check automatically that the sequence ends cleanly.
 *
 * Checks (exit 1 on failure), per view:
 *  1. END   : the final frame is pixel-identical to the static (no-effect) frame, so nothing is left displaced, grey or hidden.
 *  2. START : the first frame reveals clearly less terrain than the final one (the ring really starts at the player).
 *  3. SPREAD: (elevated views) revealed terrain grows over time (the ring spreads outward) and the effect shows grey while up (ghost pixels > 0 mid-sequence).
 * Output: reveal-frames/*.png, reveal-sheet-<view>.png (contact sheet), reveal-<view>.gif (animation).
 * It cannot show real GPU output, fog, lighting, biome tint or driver behaviour; see docs/MANUAL_RENDER_TEST.md.
 * Usage: bash tools/preview/run-reveal-preview.sh [outDir]
 */
public class RevealPreview {
    static final int W = 480, H = 270;
    static final double FOV_V = Math.toRadians(70), NEAR = 0.3;
    static final int SKY = 0xFF8FB8E8;
    static final long SEC = 1_000_000_000L;

    record View(String name, int cx, int cz, double yaw, double pitchDown, double eyeUp, boolean gif) { }
    /** Half-edge (blocks) of the square around the player that vanilla chunks cover; tiles fully inside it are not drawn (as in the game). -Dawre.preview.vanilla=N */
    static final double VANILLA = Double.parseDouble(System.getProperty("awre.preview.vanilla", "96"));

    /** One drawable tile: camera-relative float vertices (before the effect), colours, indices and its key/footprint. */
    record Tile(TileKey key, double minX, double minZ, double size, float[] rel, int[] colors, int[] indices, boolean vanilla) { }
    /** Flat stand-in for terrain that vanilla chunks draw in the game (no effect there; excluded from every metric). */
    static final int VAN = 0xFF6E8F5A;

    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : "reveal-frames"); out.mkdirs();
        RevealAnimator.Params params = RevealAnimator.Params.fromProperties(System::getProperty, m -> System.err.println("reveal: " + m));
        System.out.println("params: " + params);
        int bad = 0;
        View[] views = { new View("ground", 0, 0, 0.0, 0, 0, false),
                new View("overhead", 0, 0, 0.0, Math.toRadians(14), 60, true),
                new View("large", 62500, 62500, 4.0, Math.toRadians(14), 60, false) };
        for (View v : views) bad += run(v, params, out);
        if (bad > 0) { System.out.println("REVEAL-PREVIEW FAIL: " + bad + " check(s) failed"); System.exit(1); }
        System.out.println("REVEAL-PREVIEW PASS (CPU model of the effect; the real shader and real frames are verified in game)");
    }

    static int run(View v, RevealAnimator.Params params, File out) throws Exception {
        LodModel m = LodModel.defaults();
        double camX = v.cx() * 16 + 8.0, camZ = v.cz() * 16 + 8.0, radius = 700;
        TerrainStore store = new TerrainStore(m.maxLod(), TerrainStore.Reduce.MEAN, 20_000);
        int cr = (int) (radius / 16) + 20;
        SyntheticTerrain.fill(store, v.cx() - cr, v.cz() - cr, v.cx() + cr, v.cz() + cr, 1234);
        var pal = SyntheticTerrain.palette();
        LodPlan plan = new LodPlanner(m).plan(camX, camZ, radius, null);
        double camY = SyntheticTerrain.heightAt((int) camX, (int) camZ, 1234) + 3 + v.eyeUp();
        List<Tile> tiles = new ArrayList<>();
        for (LodKey k : plan.leaves()) {
            LodTile t = store.tile(k); if (t == null) continue;
            double tsz = m.tileSize(k.lod()), tx0 = k.tx() * tsz, tz0 = k.tz() * tsz;
            boolean underVanilla = tx0 >= camX - VANILLA && tx0 + tsz <= camX + VANILLA && tz0 >= camZ - VANILLA && tz0 + tsz <= camZ + VANILLA;   // vanilla chunks draw this
            TerrainMesher.Mesh mesh = TerrainMesher.meshLocalToFloor(t, id -> id >= 0 && id < pal.size() ? pal.keyOf(id) : "minecraft:air", -64);
            double offX = mesh.originX - camX, offZ = mesh.originZ - camZ;             // double first (CameraRelative), then float vertices
            float[] rel = new float[mesh.vertexCount() * 3];
            for (int i = 0; i < mesh.vertexCount(); i++) {
                rel[i * 3] = (float) (mesh.positions[i * 3] + (float) offX); rel[i * 3 + 1] = (float) (mesh.positions[i * 3 + 1] - camY); rel[i * 3 + 2] = (float) (mesh.positions[i * 3 + 2] + (float) offZ);
            }
            double size = m.tileSize(k.lod());
            tiles.add(new Tile(new TileKey(1, 2, "minecraft:overworld", k.tx(), k.tz(), k.lod()), mesh.originX, mesh.originZ, size, rel, mesh.colors, mesh.indices, underVanilla));
        }
        System.out.printf("%s: %d tiles%n", v.name(), tiles.size());

        double total = params.maxDelay() + params.duration() + 1.0;
        double[] times = new double[(int) Math.round(total / 0.4) + 1];
        for (int i = 0; i < times.length; i++) times[i] = i * 0.4;
        times[0] = 0.05;                                                                    // the very first moment of the session
        // static reference: the same pipeline with the effect off
        int[] staticImg = render(tiles, v, params, camX, camZ, -1, null);
        int staticCovered = covered(staticImg);
        // run the REAL stateful animator: tiles first drawn at t=0 (initial burst), uniforms re-queried every frame
        RevealAnimator anim = new RevealAnimator(params, true);
        for (Tile t : tiles) anim.forDraw(t.key(), t.minX(), t.minZ(), t.size(), camX, camZ, 0);
        List<BufferedImage> frames = new ArrayList<>();
        int[] cov = new int[times.length], ghost = new int[times.length];
        for (int i = 0; i < times.length; i++) {
            int[] img = render(tiles, v, params, camX, camZ, (long) (times[i] * SEC), anim);
            cov[i] = covered(img); ghost[i] = ghostPixels(img, staticImg);
            BufferedImage bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); bi.setRGB(0, 0, W, H, img, 0, W);
            frames.add(bi);
            if (i == times.length - 1) cov[i] = covered(img);
            if (i == times.length - 1 && !java.util.Arrays.equals(img, staticImg)) { System.out.println("  CHECK END failed: final frame differs from the static frame in " + diff(img, staticImg) + " pixels"); return 1; }
        }
        sheet(frames, times, new File(out, "reveal-sheet-" + v.name() + ".png"));
        for (int i : new int[]{0, times.length / 4, times.length / 2, times.length - 1}) javax.imageio.ImageIO.write(frames.get(i), "png", new File(out, String.format("reveal-%s-t%.1f.png", v.name(), times[i])));
        if (v.gif()) try { gif(frames, new File(out, "reveal-" + v.name() + ".gif"), 120); } catch (Exception e) { System.out.println("  (gif not written: " + e + ")"); }

        int fails = 0, midIdx = 0, maxGhost = 0;
        for (int i = 0; i < times.length; i++) if (ghost[i] > maxGhost) { maxGhost = ghost[i]; midIdx = i; }
        System.out.printf("  covered px  start=%d (t=%.2fs)  mid=%d  final=%d  static=%d%n", cov[0], times[0], cov[times.length / 2], cov[times.length - 1], staticCovered);
        System.out.printf("  ghost-grey px: max %d at t=%.1fs, final %d%n", maxGhost, times[midIdx], ghost[times.length - 1]);
        if (!(cov[0] < 0.6 * staticCovered)) { System.out.println("  CHECK START failed: first frame already shows " + cov[0] + " of " + staticCovered + " px"); fails++; }
        int nonDecreasing = 0; for (int i = 1; i < times.length; i++) if (cov[i] >= cov[i - 1] - staticCovered / 50) nonDecreasing++;
        // the low ground view's skyline legitimately rises and falls with the lift, so growth of revealed area is only meaningful from above
        if (v.eyeUp() > 0 && nonDecreasing < times.length - 3) { System.out.println("  CHECK SPREAD failed: revealed area does not grow steadily (" + nonDecreasing + "/" + (times.length - 1) + " steps)"); fails++; }
        if (maxGhost == 0 && params.greyPeak() > 0.05) { System.out.println("  CHECK GREY failed: no ghost-grey pixels at any time"); fails++; }
        if (ghost[times.length - 1] != 0) { System.out.println("  CHECK END failed: grey pixels remain in the final frame"); fails++; }
        System.out.println(fails == 0 ? "  " + v.name() + ": ok" : "  " + v.name() + ": " + fails + " failed");
        return fails;
    }

    /** now < 0 = static (no effect). Otherwise the uniforms come from the stateful animator at that time. */
    static int[] render(List<Tile> tiles, View v, RevealAnimator.Params p, double camX, double camZ, long now, RevealAnimator anim) {
        double[] zbuf = new double[W * H]; java.util.Arrays.fill(zbuf, Double.POSITIVE_INFINITY);
        int[] img = new int[W * H]; java.util.Arrays.fill(img, SKY);
        double f = (H / 2.0) / Math.tan(FOV_V / 2), cy = Math.cos(v.yaw()), sy = Math.sin(v.yaw());
        for (Tile t : tiles) {
            RevealAnimator.Uniform u = (now < 0 || t.vanilla()) ? RevealAnimator.Uniform.NONE : anim.forDraw(t.key(), t.minX(), t.minZ(), t.size(), camX, camZ, now);
            int vc = t.colors().length;
            double[][] cam = new double[vc][3]; float[] tau = new float[vc], grey = new float[vc];
            for (int i = 0; i < vc; i++) {
                float rx = t.rel()[i * 3], ry = t.rel()[i * 3 + 1], rz = t.rel()[i * 3 + 2];
                // ---- the vertex shader, in float ----
                float dist = (float) Math.hypot(rx - u.originRelX(), rz - u.originRelZ());
                float tv = Math.min(u.tileAge(), u.revealAge() - Math.min(dist / (float) p.speed(), (float) p.maxDelay()));
                float s = tv / (float) p.duration();
                float lift = 0, g = 0;
                if (s >= 0 && s < 1) { lift = (float) RevealAnimator.lift(p, s); g = (float) RevealAnimator.grey(p, s); }
                ry += lift; tau[i] = tv; grey[i] = g;
                double x1 = rx * cy - rz * sy, z1 = rx * sy + rz * cy;               // yaw
                cam[i][0] = x1; cam[i][1] = ry * Math.cos(v.pitchDown()) - z1 * Math.sin(v.pitchDown()); cam[i][2] = ry * Math.sin(v.pitchDown()) + z1 * Math.cos(v.pitchDown());   // pitch down
            }
            for (int ti = 0; ti + 2 < t.indices().length; ti += 3) {
                int i0 = t.indices()[ti], i1 = t.indices()[ti + 1], i2 = t.indices()[ti + 2];
                raster(new int[]{i0, i1, i2}, cam, tau, grey, t.colors(), t.vanilla(), f, zbuf, img);
            }
        }
        return img;
    }

    static void raster(int[] idx, double[][] cam, float[] tau, float[] grey, int[] colors, boolean vanilla, double f, double[] zbuf, int[] img) {
        // near-plane clip in camera space, carrying (tau, grey, colour index of vertex 0) as flat/linear attributes
        List<double[]> poly = new ArrayList<>(); // x, y, z, tau, grey
        for (int i = 0; i < 3; i++) {
            int a = idx[i], b = idx[(i + 1) % 3];
            double[] p = {cam[a][0], cam[a][1], cam[a][2], tau[a], grey[a]}, q = {cam[b][0], cam[b][1], cam[b][2], tau[b], grey[b]};
            boolean pin = p[2] >= NEAR, qin = q[2] >= NEAR;
            if (pin) poly.add(p);
            if (pin != qin) { double k = (NEAR - p[2]) / (q[2] - p[2]); double[] r = new double[5]; for (int c = 0; c < 5; c++) r[c] = p[c] + k * (q[c] - p[c]); r[2] = NEAR; poly.add(r); }
        }
        if (poly.size() < 3) return;
        int col = vanilla ? VAN : colors[idx[0]];
        for (int i = 1; i + 1 < poly.size(); i++) fill(poly.get(0), poly.get(i), poly.get(i + 1), col, f, zbuf, img);
    }

    static void fill(double[] a, double[] b, double[] c, int col, double f, double[] zbuf, int[] img) {
        double ax = W / 2.0 + f * a[0] / a[2], ay = H / 2.0 - f * a[1] / a[2], bx = W / 2.0 + f * b[0] / b[2], by = H / 2.0 - f * b[1] / b[2], cx = W / 2.0 + f * c[0] / c[2], cy = H / 2.0 - f * c[1] / c[2];
        int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = Math.min(W - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = Math.min(H - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        double den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        if (Math.abs(den) < 1e-12) return;
        double r0 = ((col >> 16) & 255) / 255.0, g0 = ((col >> 8) & 255) / 255.0, b0 = (col & 255) / 255.0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            double px = x + 0.5, py = y + 0.5;
            double l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den, l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den, l3 = 1 - l1 - l2;
            if (l1 < -1e-6 || l2 < -1e-6 || l3 < -1e-6) continue;
            double w1 = l1 / a[2], w2 = l2 / b[2], w3 = l3 / c[2], ws = w1 + w2 + w3, z = 1 / ws;   // perspective-correct varyings, like the GPU
            int i = y * W + x;
            if (z >= zbuf[i]) continue;
            double tau = (w1 * a[3] + w2 * b[3] + w3 * c[3]) / ws, grey = (w1 * a[4] + w2 * b[4] + w3 * c[4]) / ws;
            if (tau < 0) continue;                                                   // fragment shader: discard (wave not here yet)
            double[] mixed = RevealAnimator.greyMix(r0, g0, b0, grey);
            zbuf[i] = z;
            img[i] = 0xFF000000 | (to8(mixed[0]) << 16) | (to8(mixed[1]) << 8) | to8(mixed[2]);
        }
    }

    static int to8(double v) { return (int) Math.max(0, Math.min(255, Math.round(v * 255))); }
    static int covered(int[] img) { int n = 0; for (int p : img) if (p != SKY && p != VAN) n++; return n; }
    static int diff(int[] a, int[] b) { int n = 0; for (int i = 0; i < a.length; i++) if (a[i] != b[i]) n++; return n; }

    static double sat(int p) {
        double r = ((p >> 16) & 255) / 255.0, g = ((p >> 8) & 255) / 255.0, b = (p & 255) / 255.0, mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return mx <= 0 ? 0 : (mx - mn) / mx;
    }

    /** Pixels visibly greyer than the same pixel of the static frame (the "ghost" look). */
    static int ghostPixels(int[] img, int[] ref) {
        int n = 0;
        for (int i = 0; i < img.length; i++) if (img[i] != SKY && img[i] != VAN && ref[i] != SKY && ref[i] != VAN && sat(ref[i]) > 0.25 && sat(img[i]) < sat(ref[i]) - 0.15) n++;
        return n;
    }

    static void sheet(List<BufferedImage> frames, double[] times, File f) throws Exception {
        int[] pick = new int[8];
        for (int i = 0; i < pick.length; i++) pick[i] = Math.round(i * (frames.size() - 1) / 7f);
        int cols = 4, rows = 2, tw = W / 2, th = H / 2;
        BufferedImage s = new BufferedImage(cols * tw, rows * th, BufferedImage.TYPE_INT_RGB);
        var g = s.createGraphics();
        for (int i = 0; i < pick.length; i++) {
            g.drawImage(frames.get(pick[i]), (i % cols) * tw, (i / cols) * th, tw, th, null);
            g.setColor(java.awt.Color.WHITE); g.drawString(String.format("t=%.1fs", times[pick[i]]), (i % cols) * tw + 6, (i / cols) * th + 14);
        }
        g.dispose();
        javax.imageio.ImageIO.write(s, "png", f);
    }

    static void gif(List<BufferedImage> frames, File f, int delayCs) throws Exception {
        var writer = javax.imageio.ImageIO.getImageWritersByFormatName("gif").next();
        try (var os = javax.imageio.ImageIO.createImageOutputStream(f)) {
            writer.setOutput(os); writer.prepareWriteSequence(null);
            for (int i = 0; i < frames.size(); i++) {
                var param = writer.getDefaultWriteParam();
                var meta = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(frames.get(i)), param);
                String fmt = meta.getNativeMetadataFormatName();
                var root = (javax.imageio.metadata.IIOMetadataNode) meta.getAsTree(fmt);
                var gce = new javax.imageio.metadata.IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none"); gce.setAttribute("userInputFlag", "FALSE"); gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(delayCs)); gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                if (i == 0) {
                    var apps = new javax.imageio.metadata.IIOMetadataNode("ApplicationExtensions");
                    var app = new javax.imageio.metadata.IIOMetadataNode("ApplicationExtension");
                    app.setAttribute("applicationID", "NETSCAPE"); app.setAttribute("authenticationCode", "2.0");
                    app.setUserObject(new byte[]{1, 0, 0}); apps.appendChild(app); root.appendChild(apps);
                }
                meta.setFromTree(fmt, root);
                writer.writeToSequence(new javax.imageio.IIOImage(frames.get(i), null, meta), param);
            }
            writer.endWriteSequence();
        }
    }
}
