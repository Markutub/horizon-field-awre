import dev.horizonfield.core.*;
import java.util.*;

/** Synthetic benchmark: fractal value-noise heightfield. NOT a Minecraft measurement. */
public class SweepBench {
    static double hash(int x, int z, long seed) {
        long h = x * 374761393L + z * 668265263L + seed * 1442695040888963407L;
        h = (h ^ (h >>> 13)) * 1274126177L; h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }
    static double noise(double x, double z, long seed) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0; fx = fx*fx*(3-2*fx); fz = fz*fz*(3-2*fz);
        double a = hash(x0,z0,seed), b = hash(x0+1,z0,seed), c = hash(x0,z0+1,seed), d = hash(x0+1,z0+1,seed);
        return a + (b-a)*fx + (c-a)*fz + (a-b-c+d)*fx*fz;
    }
    static int height(int bx, int bz, double roughness) {
        double h = 0, amp = 1, f = 1.0/256, sum = 0;
        for (int o = 0; o < 5; o++) { h += amp * noise(bx*f, bz*f, 7+o); sum += amp; amp *= 0.5; f *= 2; }
        return (int) (50 + (h/sum) * roughness);
    }
    public static void main(String[] a) {
        for (double rough : new double[]{40, 120, 220}) {
            int R = 40; // chunks
            HeightPyramid p = new HeightPyramid(6);
            for (int cz = -R; cz <= R; cz++) for (int cx = -R; cx <= R; cx++) {
                short[] top = new short[256], m = new short[256];
                for (int i = 0; i < 256; i++) { top[i] = (short) height(cx*16 + (i&15), cz*16 + (i>>4), rough); m[i] = 1; }
                p.put(new ColumnTile(cx, cz, top, m, Confidence.OBSERVED, 0));
            }
            int eyeY = height(8, 8, rough) + 2;
            VisibilityParams q = new VisibilityParams(8, eyeY, 8, 16*8, 16*32, 1024, 8);
            VisibilityResult r = null; long best = Long.MAX_VALUE;
            for (int i = 0; i < 30; i++) { r = VisibilitySweep.run(p, q); best = Math.min(best, r.nanos()); }
            int ringChunks = 0;
            for (int cz = -R; cz <= R; cz++) for (int cx = -R; cx <= R; cx++) {
                double d = Math.hypot(cx*16+8-8, cz*16+8-8);
                if (d >= q.minRadius() && d <= q.maxRadius()) ringChunks++;
            }
            Set<Long> vis = r.toChunkSet();
            List<ShellPrism> pr = ShellMesher.mesh(r.cells(), q, 100000, 4);
            System.out.printf("roughness=%.0f ring chunks=%d visible=%d (%.1f%%) probes=%d best=%.2f ms prisms(merged)=%d%n",
                rough, ringChunks, vis.size(), 100.0*vis.size()/ringChunks, r.probes(), best/1e6, pr.size());
        }
    }
}
