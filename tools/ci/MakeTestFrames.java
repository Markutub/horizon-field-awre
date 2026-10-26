import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/** Writes synthetic frames for frame-check-test.sh (320x180): a sky gradient with dark ground at the bottom, plus variants. */
public class MakeTestFrames {
    static final int W = 320, H = 180;

    static BufferedImage base() {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int rgb = y < 0.75 * H ? rgb(110 + y / 4, 160 + y / 5, 235 - y / 6) : rgb(60, 70, 55);                  // sky gradient, then vanilla ground
            im.setRGB(x, y, rgb);
        }
        return im;
    }
    static int rgb(int r, int g, int b) { return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b); }

    /** Terrain-coloured hills across the horizon band (rows 0.50H..0.74H), like distant AWRE terrain below the sky. */
    static BufferedImage withTerrain() {
        BufferedImage im = base();
        Random r = new Random(1);
        for (int x = 0; x < W; x++) {
            int top = (int) (0.55 * H + 8 * Math.sin(x / 17.0) + 5 * Math.sin(x / 5.0));
            for (int y = top; y < 0.75 * H; y++) im.setRGB(x, y, rgb(70 + r.nextInt(25), 120 + r.nextInt(30), 60 + r.nextInt(20)));
        }
        return im;
    }

    static void save(BufferedImage im, File dir, String name) throws Exception { ImageIO.write(im, "png", new File(dir, name)); }

    public static void main(String[] a) throws Exception {
        File d = new File(a[0]); d.mkdirs();
        save(base(), d, "control.png");
        save(withTerrain(), d, "awre.png");
        BufferedImage noisy = base();                                          // a second control run: tiny difference only
        Random r = new Random(2);
        for (int i = 0; i < 40; i++) noisy.setRGB(r.nextInt(W), r.nextInt(H), 0xFFFFFF);
        save(noisy, d, "control2.png");
        BufferedImage unstable = base();                                       // a second control run where a large part of the band changed (clouds, a mob)
        for (int y = (int) (0.4 * H); y < 0.7 * H; y++) for (int x = 0; x < W / 2; x++) unstable.setRGB(x, y, rgb(240, 240, 240));
        save(unstable, d, "control-unstable.png");
        BufferedImage hudOnly = base();                                        // differs from the control only OUTSIDE the horizon band (HUD, hand)
        for (int y = (int) (0.80 * H); y < H; y++) for (int x = 0; x < W; x++) hudOnly.setRGB(x, y, rgb(200, 30, 30));
        for (int y = 0; y < (int) (0.2 * H); y++) for (int x = 0; x < W; x++) hudOnly.setRGB(x, y, rgb(10, 10, 10));
        save(hudOnly, d, "hud-only.png");
        BufferedImage black = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);   // a failed GL context: one flat colour
        save(black, d, "black.png");
        save(new BufferedImage(W / 2, H / 2, BufferedImage.TYPE_INT_RGB), d, "small.png");
        java.nio.file.Files.writeString(new File(d, "notimage.png").toPath(), "not a png");
    }
}
