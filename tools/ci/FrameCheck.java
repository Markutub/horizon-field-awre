import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Decides from two real frames whether AWRE terrain is visible: the frame rendered with AWRE on versus the CONTROL frame of the same scene
 * with AWRE rendering off (-Dawre.render=off), looking at the horizon with a fixed camera, fixed time and no GUI.
 *
 * Usage: java tools/ci/FrameCheck.java --awre awre.png --control control.png [--noise control2.png]
 *            [--band 0.35,0.70] [--pixel-delta 24] [--min-diff 0.05] [--max-noise 0.01]
 * Exit 0 = PASS, 1 = FAIL (the frames say AWRE is NOT visible, or the scene is unstable), 2 = usage / unreadable input.
 *
 * PASS needs all of: (1) at least --min-diff of the pixels in the horizon band differ from the control by more than --pixel-delta in some
 * channel; (2) when --noise (a second control run) is given, it differs from the first control by at most --max-noise (otherwise the scene is
 * not stable enough for a difference to mean anything); (3) the AWRE frame's band is not one flat colour (a failed GL context gives that).
 * Known limits: any visible change inside the band (a cloud, a mob, a particle) counts as a difference, so the scene must be controlled; this
 * proves "something changed in the horizon band", the control run is what makes it mean "AWRE terrain" - it is not a terrain classifier.
 */
public class FrameCheck {
    public static void main(String[] a) throws Exception {
        String awre = null, control = null, noise = null;
        double b0 = 0.35, b1 = 0.70, minDiff = 0.05, maxNoise = 0.01;
        int delta = 24;
        try {
            for (int i = 0; i < a.length; i++) {
                switch (a[i]) {
                    case "--awre" -> awre = a[++i];
                    case "--control" -> control = a[++i];
                    case "--noise" -> noise = a[++i];
                    case "--band" -> { String[] p = a[++i].split(","); b0 = Double.parseDouble(p[0]); b1 = Double.parseDouble(p[1]); }
                    case "--pixel-delta" -> delta = Integer.parseInt(a[++i]);
                    case "--min-diff" -> minDiff = Double.parseDouble(a[++i]);
                    case "--max-noise" -> maxNoise = Double.parseDouble(a[++i]);
                    default -> throw new IllegalArgumentException("unknown option " + a[i]);
                }
            }
            if (awre == null || control == null || !(b0 >= 0 && b0 < b1 && b1 <= 1) || delta < 0) throw new IllegalArgumentException("need --awre and --control, band 0<=a<b<=1");
        } catch (RuntimeException e) { System.out.println("FRAMECHECK ERROR usage: " + e.getMessage()); System.exit(2); }

        BufferedImage fa, fc, fn = null;
        try {
            fa = read(awre); fc = read(control); if (noise != null) fn = read(noise);
        } catch (Exception e) { System.out.println("FRAMECHECK ERROR cannot read input: " + e.getMessage()); System.exit(2); return; }
        if (fa.getWidth() != fc.getWidth() || fa.getHeight() != fc.getHeight() || (fn != null && (fn.getWidth() != fc.getWidth() || fn.getHeight() != fc.getHeight()))) {
            System.out.println("FRAMECHECK ERROR frame sizes differ (" + fa.getWidth() + "x" + fa.getHeight() + " vs " + fc.getWidth() + "x" + fc.getHeight() + ")"); System.exit(2);
        }
        int y0 = (int) Math.floor(b0 * fa.getHeight()), y1 = Math.max(y0 + 1, (int) Math.ceil(b1 * fa.getHeight()));
        double diff = fraction(fa, fc, y0, y1, delta);
        double noiseDiff = fn == null ? 0 : fraction(fc, fn, y0, y1, delta);
        int colours = distinctColours(fa, y0, y1);
        boolean flat = colours <= 1;
        boolean visible = diff >= minDiff, stable = fn == null || noiseDiff <= maxNoise;
        String why = flat ? "the AWRE frame's horizon band is one flat colour (no GL output?)" : !stable ? "the control scene is unstable (noise " + pct(noiseDiff) + " > " + pct(maxNoise) + ")" : !visible ? "the AWRE frame is (almost) the control frame: AWRE draws nothing in the band" : "";
        boolean pass = visible && stable && !flat;
        System.out.println("FRAMECHECK " + (pass ? "PASS" : "FAIL") + " band=rows " + y0 + ".." + (y1 - 1) + " of " + fa.getHeight() + " awre_vs_control=" + pct(diff) + " (min " + pct(minDiff) + ") control_noise=" + (fn == null ? "n/a" : pct(noiseDiff) + " (max " + pct(maxNoise) + ")") + " distinct_colours=" + colours + (pass ? "" : " reason: " + why));
        System.exit(pass ? 0 : 1);
    }

    private static BufferedImage read(String path) throws Exception {
        BufferedImage im = ImageIO.read(new File(path));
        if (im == null) throw new IllegalArgumentException(path + " is not a readable image");
        return im;
    }

    /** Fraction of the pixels in rows [y0,y1) whose largest RGB channel difference exceeds delta. */
    static double fraction(BufferedImage x, BufferedImage y, int y0, int y1, int delta) {
        long n = 0, d = 0;
        for (int row = y0; row < y1; row++) for (int col = 0; col < x.getWidth(); col++) {
            int p = x.getRGB(col, row), q = y.getRGB(col, row);
            int m = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)), Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
            n++; if (m > delta) d++;
        }
        return n == 0 ? 0 : (double) d / n;
    }

    static int distinctColours(BufferedImage im, int y0, int y1) {
        java.util.HashSet<Integer> s = new java.util.HashSet<>();
        for (int row = y0; row < y1; row++) for (int col = 0; col < im.getWidth(); col++) s.add(im.getRGB(col, row) & 0xFFFFFF);
        return s.size();
    }

    private static String pct(double f) { return String.format(java.util.Locale.ROOT, "%.2f%%", f * 100); }
}
