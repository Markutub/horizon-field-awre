package dev.horizonfield.core;

import java.util.*;

/**
 * SYNTHETIC streaming simulation for hypotheses H1 (clipmap rings) and H6 (visibility-ranked order).
 * Terrain = fractal noise from CodecBench (NOT Minecraft). Ground truth visibility = independent dense horizon ray
 * march (2048 rays, 2-block steps) over the exact heightfield, NOT the VisibilitySweep under test.
 * Tile cost = bytes of TileCodec.encode on tiles built at that level (raw, no batch deflate).
 * Strategies: S1 level-0 nearest-first | S2 level-0 sweep-ranked | S3 clipmap rings nearest-first | S4 clipmap sweep-ranked.
 * Clipmap rings (blocks): L0 [128,256) 1-chunk tiles, L1 [256,384) 2-chunk tiles, L2 [384,512] 4-chunk tiles.
 * Every strategy eventually wants the same ring [128,512]; ranking never drops tiles (it only reorders).
 */
public class StreamSim {
    static final double MINR = 128, MAXR = 512, ROUGH = 120;
    static final double[] RING_LO = {128, 256, 384}, RING_HI = {256, 384, 513};
    static int GX0, GZ0, GW, GH; static short[] grid;

    static int gridH(double x, double z) {
        int gx = ((int) Math.floor(x) - GX0) >> 1, gz = ((int) Math.floor(z) - GZ0) >> 1;
        gx = Math.max(0, Math.min(GW - 1, gx)); gz = Math.max(0, Math.min(GH - 1, gz));
        return grid[gz * GW + gx];
    }
    static int fl(double v) { return Math.floorDiv((int) Math.floor(v), 16); }
    static long tk(int level, int tx, int tz) { return ((long) level << 58) | ((long) (tx & 0x1FFFFFFF) << 29) | (tz & 0x1FFFFFFFL); }

    record TileData(short[] top, int bytes) { }
    static final Map<Long, TileData> MEMO = new HashMap<>();
    static TileData tile(int level, int tx, int tz) {
        return MEMO.computeIfAbsent(tk(level, tx, tz), k -> {
            int s = 1 << level; short[] top = new short[256], m = new short[256];
            for (int i = 0; i < 256; i++) {
                int bx = tx * 16 * s + (i & 15) * s, bz = tz * 16 * s + (i >> 4) * s;
                top[i] = (short) CodecBench.height(bx, bz, ROUGH); m[i] = CodecBench.material(top[i], bx, bz);
            }
            return new TileData(top, TileCodec.encode(new ColumnTile(tx, tz, top, m, Confidence.OBSERVED, 0L)).length);
        });
    }

    static Set<Long> truth(double px, double pz, double eyeY) {
        Set<Long> out = new HashSet<>(); int rays = 2048;
        for (int r = 0; r < rays; r++) {
            double a = 2 * Math.PI * r / rays, dx = Math.cos(a), dz = Math.sin(a), maxSlope = -1e9;
            for (double d = 2; d <= MAXR; d += 2) {
                double x = px + dx * d, z = pz + dz * d, slope = (gridH(x, z) - eyeY) / d;
                if (slope >= maxSlope) {
                    maxSlope = slope;
                    int cx = fl(x), cz = fl(z); double cd = Math.hypot(cx * 16 + 8 - px, cz * 16 + 8 - pz);
                    if (d >= MINR && cd >= MINR && cd <= MAXR) out.add(HeightPyramid.key(cx, cz)); // same centre-distance filter as the sweep set
                }
            }
        }
        return out;
    }

    record Cand(int level, int tx, int tz, double dist, boolean vis) { }
    static boolean anyIn(Set<Long> set, int level, int tx, int tz) {
        int s = 1 << level;
        for (int dz = 0; dz < s; dz++) for (int dx = 0; dx < s; dx++) if (set.contains(HeightPyramid.key(tx * s + dx, tz * s + dz))) return true;
        return false;
    }

    static void scenario(String label, double speed, double eyeOff, int budget, int steps, HeightPyramid pyr) {
        double[] px = new double[steps], pz = new double[steps], ey = new double[steps];
        List<Set<Long>> truths = new ArrayList<>(), sweeps = new ArrayList<>();
        double miss = 0, ratio = 0;
        for (int i = 0; i < steps; i++) {
            px[i] = i * speed * 16 + 8; pz[i] = 8; ey[i] = CodecBench.height((int) px[i], 8, ROUGH) + eyeOff;
            Set<Long> t = truth(px[i], pz[i], ey[i]);
            Set<Long> sw = new HashSet<>();
            for (long k : VisibilitySweep.run(pyr, new VisibilityParams(px[i], ey[i], pz[i], MINR, MAXR, 1024, 8)).toChunkSet()) {
                int cx = (int) (k >> 32), cz = (int) k; double d = Math.hypot(cx * 16 + 8 - px[i], cz * 16 + 8 - pz[i]);
                if (d >= MINR && d <= MAXR) sw.add(k);
            }
            int m = 0; for (long k : t) if (!sw.contains(k)) m++;
            miss += m / (double) t.size(); ratio += sw.size() / (double) t.size();
            truths.add(t); sweeps.add(sw);
        }
        System.out.printf("%n== %s: speed=%.1f chunks/step, eye=ground+%.0f, budget=%d B/step, %d steps%n", label, speed, eyeOff, budget, steps);
        System.out.printf("   sweep vs truth: false-occlusion (truth-visible chunks the sweep missed) = %.2f%%, sweep set / truth set = %.2fx%n", 100 * miss / steps, ratio / steps);
        System.out.printf("   %-26s %8s %9s %8s %9s %9s %9s %9s %10s %10s %9s%n", "strategy", "tiles", "KB sent", "useful%", "t90(step)", "cov(all)", "cov(late)", "err(late)", "relevel t%", "relevel KB%", "resendKey");
        String[] names = {"S1 L0 nearest-first", "S2 L0 sweep-ranked", "S3 clipmap nearest", "S4 clipmap sweep-ranked", "S5 clipmap keep-until-repl."};
        for (int mode = 0; mode < 5; mode++) {
            boolean clip = mode >= 2, ranked = (mode & 1) == 1, retain = mode == 4;
            Map<Long, int[]> cache = new HashMap<>(); // key -> {level,tx,tz}
            long tiles = 0, bytesTot = 0, useful = 0, relevel = 0, relevelBytes = 0, resendSame = 0; int t90 = -1;
            Map<Long, Integer> lastLevel = new HashMap<>(); Set<Long> everKey = new HashSet<>(); double covAll = 0, covLate = 0, errLate = 0; int lateN = 0;
            for (int i = 0; i < steps; i++) {
                double x = px[i], z = pz[i]; Set<Long> sw = sweeps.get(i), tr = truths.get(i);
                // evict tiles whose centre left their ring (2-chunk hysteresis)
                Set<Long> keys = new HashSet<>(cache.keySet());
                cache.values().removeIf(c -> {
                    int s = 1 << c[0]; double d = Math.hypot((c[1] + 0.5) * 16 * s - x, (c[2] + 0.5) * 16 * s - z);
                    double lo = clip ? RING_LO[c[0]] : MINR, hi = clip ? RING_HI[c[0]] : MAXR;
                    boolean outside = d < lo - 32 || d > hi + 32;
                    if (!retain || !outside) return outside;
                    if (d < MINR - 32 || d > MAXR + 32) return true;
                    int L2 = d < RING_HI[0] ? 0 : d < RING_HI[1] ? 1 : 2;           // ring the tile's centre is in now
                    if (L2 > c[0]) return keys.contains(tk(L2, (c[1] * s) >> L2, (c[2] * s) >> L2)); // fine tile: coarse parent cached?
                    int f = 1 << (c[0] - L2);                                        // coarse tile: all finer children cached?
                    for (int dz = 0; dz < f; dz++) for (int dx = 0; dx < f; dx++) if (!keys.contains(tk(L2, c[1] * f + dx, c[2] * f + dz))) return false;
                    return true;
                });
                List<Cand> cands = new ArrayList<>();
                for (int L = 0; L <= (clip ? 2 : 0); L++) {
                    int s = 1 << L; double lo = clip ? RING_LO[L] : MINR, hi = clip ? RING_HI[L] : MAXR;
                    int x0 = fl((x - hi) / s), x1 = fl((x + hi) / s), z0 = fl((z - hi) / s), z1 = fl((z + hi) / s);
                    for (int tz = z0; tz <= z1; tz++) for (int tx = x0; tx <= x1; tx++) {
                        double d = Math.hypot((tx + 0.5) * 16 * s - x, (tz + 0.5) * 16 * s - z);
                        if (d < lo || d >= hi || cache.containsKey(tk(L, tx, tz))) continue;
                        cands.add(new Cand(L, tx, tz, d, ranked && anyIn(sw, L, tx, tz)));
                    }
                }
                cands.sort(Comparator.comparing((Cand c) -> ranked ? (c.vis() ? 0 : 1) : 0).thenComparingDouble(Cand::dist));
                int used = 0;
                for (Cand c : cands) {
                    TileData td = tile(c.level(), c.tx(), c.tz());
                    if (used > 0 && used + td.bytes() > budget) break;
                    used += td.bytes(); tiles++; bytesTot += td.bytes();
                    if (anyIn(tr, c.level(), c.tx(), c.tz())) useful++;
                    cache.put(tk(c.level(), c.tx(), c.tz()), new int[]{c.level(), c.tx(), c.tz()});
                    int fs = 1 << c.level(); boolean lv = false;
                    for (int dz = 0; dz < fs; dz++) for (int dx = 0; dx < fs; dx++) {
                        Integer ll = lastLevel.put(HeightPyramid.key(c.tx() * fs + dx, c.tz() * fs + dz), c.level());
                        if (ll != null && ll != c.level()) lv = true;
                    }
                    if (lv) { relevel++; relevelBytes += td.bytes(); }
                    if (!everKey.add(tk(c.level(), c.tx(), c.tz()))) resendSame++;
                }
                int covered = 0; double err = 0;
                for (long k : tr) {
                    int cx = (int) (k >> 32), cz = (int) k;
                    for (int L = 0; L <= 2; L++) {
                        if (!cache.containsKey(tk(L, cx >> L, cz >> L))) continue;
                        covered++; int s = 1 << L; TileData td = tile(L, cx >> L, cz >> L);
                        double e4 = 0;
                        int[][] offs = {{3, 3}, {11, 3}, {3, 11}, {11, 11}};
                        for (int[] o : offs) {
                            int bx = cx * 16 + o[0], bz = cz * 16 + o[1];
                            int lx = Math.max(0, Math.min(15, (bx - (cx >> L) * 16 * s) / s));
                            int lz = Math.max(0, Math.min(15, (bz - (cz >> L) * 16 * s) / s));
                            e4 += Math.abs(td.top()[lz * 16 + lx] - CodecBench.height(bx, bz, ROUGH));
                        }
                        err += e4 / 4;
                        break;
                    }
                }
                double cov = covered / (double) tr.size(); covAll += cov;
                if (t90 < 0 && cov >= 0.9) t90 = i;
                if (i >= steps / 2) { covLate += cov; errLate += covered == 0 ? 0 : err / covered; lateN++; }
            }
            System.out.printf("   %-26s %8d %9.1f %7.1f%% %9s %8.1f%% %8.1f%% %8.2f %9.1f%% %9.1f%% %9d%n", names[mode], tiles, bytesTot / 1024.0,
                    100.0 * useful / Math.max(1, tiles), t90 < 0 ? ">" + steps : String.valueOf(t90), 100 * covAll / steps, 100 * covLate / lateN, errLate / lateN,
                    100.0 * relevel / Math.max(1, tiles), 100.0 * relevelBytes / Math.max(1, bytesTot), resendSame);
        }
    }


    static Set<Long> sweepSet(HeightPyramid pyr, double x, double ey, double z, double slack) { return sweepSet(pyr, x, ey, z, slack, MINR, MAXR); }
    static Set<Long> sweepSet(HeightPyramid pyr, double x, double ey, double z, double slack, double minR, double maxR) {
        Set<Long> sw = new HashSet<>();
        for (long k : VisibilitySweep.run(pyr, new VisibilityParams(x, ey, z, minR, maxR, 1024, slack)).toChunkSet()) {
            int cx = (int) (k >> 32), cz = (int) k; double d = Math.hypot(cx * 16 + 8 - x, cz * 16 + 8 - z);
            if (d >= minR && d <= maxR) sw.add(k);
        }
        return sw;
    }

    /** Level-0 nearest-first streaming; if sweepUsed != null only tiles inside that step's (possibly stale) sweep set are candidates. */
    static double[] runL0(List<Set<Long>> truths, double[] px, double[] pz, List<Set<Long>> sweepUsed, int steps, int budget) {
        Map<Long, int[]> cache = new HashMap<>(); long tiles = 0, bytes = 0; int t90 = -1, lateN = 0;
        double covLate = 0, minLate = 1, holes = 0;
        for (int i = 0; i < steps; i++) {
            double x = px[i], z = pz[i]; Set<Long> tr = truths.get(i);
            cache.values().removeIf(c -> { double d = Math.hypot((c[1] + 0.5) * 16 - x, (c[2] + 0.5) * 16 - z); return d < MINR - 32 || d > MAXR + 32; });
            List<int[]> cands = new ArrayList<>(); List<Double> dist = new ArrayList<>();
            for (int tz = fl(z - MAXR); tz <= fl(z + MAXR); tz++) for (int tx = fl(x - MAXR); tx <= fl(x + MAXR); tx++) {
                double d = Math.hypot((tx + 0.5) * 16 - x, (tz + 0.5) * 16 - z);
                if (d < MINR || d >= MAXR || cache.containsKey(tk(0, tx, tz))) continue;
                if (sweepUsed != null && !sweepUsed.get(i).contains(HeightPyramid.key(tx, tz))) continue;
                cands.add(new int[]{tx, tz}); dist.add(d);
            }
            Integer[] order = new Integer[cands.size()]; for (int j = 0; j < order.length; j++) order[j] = j;
            Arrays.sort(order, Comparator.comparingDouble(dist::get));
            int used = 0;
            for (int j : order) {
                int[] c = cands.get(j); TileData td = tile(0, c[0], c[1]);
                if (used > 0 && used + td.bytes() > budget) break;
                used += td.bytes(); tiles++; bytes += td.bytes(); cache.put(tk(0, c[0], c[1]), new int[]{0, c[0], c[1]});
            }
            int covered = 0; for (long k : tr) if (cache.containsKey(tk(0, (int) (k >> 32), (int) k))) covered++;
            double cov = covered / (double) tr.size();
            if (t90 < 0 && cov >= 0.9) t90 = i;
            if (i >= steps / 2) { covLate += cov; minLate = Math.min(minLate, cov); holes += tr.size() - covered; lateN++; }
        }
        return new double[]{tiles, bytes / 1024.0, t90, 100 * covLate / lateN, 100 * minLate, holes / lateN};
    }

    static void cullStudy(HeightPyramid pyr) {
        int steps = 120;
        double[][] sc = {{0.3, 2, 16000}, {3.0, 60, 64000}}; String[] names = {"walk, 16 KB/step", "elytra, 64 KB/step"};
        for (int q = 0; q < 2; q++) {
            double speed = sc[q][0], off = sc[q][1]; int budget = (int) sc[q][2];
            double[] px = new double[steps], pz = new double[steps], ey = new double[steps];
            List<Set<Long>> truths = new ArrayList<>();
            for (int i = 0; i < steps; i++) { px[i] = i * speed * 16 + 8; pz[i] = 8; ey[i] = CodecBench.height((int) px[i], 8, ROUGH) + off; truths.add(truth(px[i], pz[i], ey[i])); }
            double[] base = runL0(truths, px, pz, null, steps, budget);
            System.out.printf("%n== CULL study, %s (L0 nearest-first; candidates restricted to the last sweep's visible set)%n", names[q]);
            System.out.printf("   %-30s %8s %9s %8s %8s %9s %9s %11s%n", "variant", "tiles", "KB sent", "KB save", "t90", "cov(late)", "cov(min)", "holes/step");
            System.out.printf("   %-30s %8.0f %9.1f %8s %8.0f %8.1f%% %8.1f%% %11.1f%n", "no culling (baseline)", base[0], base[1], "-", base[2], base[3], base[4], base[5]);
            for (double slack : new double[]{8, 32}) {
                List<Set<Long>> sweepAt = new ArrayList<>();
                for (int i = 0; i < steps; i++) sweepAt.add(sweepSet(pyr, px[i], ey[i], pz[i], slack));
                for (int K : new int[]{1, 4, 10}) {
                    List<Set<Long>> used = new ArrayList<>();
                    for (int i = 0; i < steps; i++) used.add(sweepAt.get((i / K) * K));
                    double[] r = runL0(truths, px, pz, used, steps, budget);
                    System.out.printf("   %-30s %8.0f %9.1f %7.1f%% %8s %8.1f%% %8.1f%% %11.1f%n", "slack=" + (int) slack + " sweep every " + K + " step" + (K > 1 ? "s" : ""),
                            r[0], r[1], 100 * (1 - r[1] / base[1]), r[2] < 0 ? ">" + steps : String.valueOf((int) r[2]), r[3], r[4], r[5]);
                }
            }
            for (int K : new int[]{4, 10}) { // slack 8, sweep radius padded by the distance moved during K steps
                double pad = speed * 16 * K; List<Set<Long>> used = new ArrayList<>(); Set<Long> cur = null;
                for (int i = 0; i < steps; i++) {
                    if (i % K == 0) cur = sweepSet(pyr, px[i], ey[i], pz[i], 8, Math.max(16, MINR - pad), MAXR + pad);
                    used.add(cur);
                }
                double[] r = runL0(truths, px, pz, used, steps, budget);
                System.out.printf("   %-30s %8.0f %9.1f %7.1f%% %8s %8.1f%% %8.1f%% %11.1f%n", "slack=8 PADDED, every " + K + " steps",
                        r[0], r[1], 100 * (1 - r[1] / base[1]), r[2] < 0 ? ">" + steps : String.valueOf((int) r[2]), r[3], r[4], r[5]);
            }
        }
    }

    /** Does the sweep miss fewer truth-visible chunks with more azimuth rays? (20 sample positions per setting) */
    static void rayStudy(HeightPyramid pyr) {
        System.out.printf("%n== sweep false-occlusion vs azimuth rays (20 positions, walk and elytra eye heights)%n");
        for (double off : new double[]{2, 60}) for (int bins : new int[]{1024, 2048, 4096}) {
            double miss = 0, ratio = 0; int n = 20;
            for (int i = 0; i < n; i++) {
                double x = i * 15 * 16 + 8, z = 8, ey = CodecBench.height((int) x, 8, ROUGH) + off;
                Set<Long> t = truth(x, z, ey), sw = new HashSet<>();
                for (long k : VisibilitySweep.run(pyr, new VisibilityParams(x, ey, z, MINR, MAXR, bins, 8)).toChunkSet()) {
                    int cx = (int) (k >> 32), cz = (int) k; double d = Math.hypot(cx * 16 + 8 - x, cz * 16 + 8 - z);
                    if (d >= MINR && d <= MAXR) sw.add(k);
                }
                int m = 0; for (long k : t) if (!sw.contains(k)) m++;
                miss += m / (double) t.size(); ratio += sw.size() / (double) t.size();
            }
            System.out.printf("   eye=ground+%-3.0f bins=%4d  miss=%.2f%%  sweep/truth=%.2fx%n", off, bins, 100 * miss / n, ratio / n);
        }
    }

    public static void main(String[] a) {
        String mode = a.length > 0 ? a[0] : "all";
        int steps = 120; double[] speeds = {0.3, 3.0};
        int maxCx = (int) (steps * 3.0) + 50; GX0 = -800; GZ0 = -800;
        GW = ((maxCx + 50) * 16 - GX0) / 2; GH = 1600 / 2; grid = new short[GW * GH];
        for (int gz = 0; gz < GH; gz++) for (int gx = 0; gx < GW; gx++) grid[gz * GW + gx] = (short) CodecBench.height(GX0 + gx * 2, GZ0 + gz * 2, ROUGH);
        HeightPyramid pyr = new HeightPyramid(6);
        for (int cz = -45; cz <= 45; cz++) for (int cx = -45; cx <= maxCx + 45; cx++) {
            short[] top = new short[256], m = new short[256];
            for (int i = 0; i < 256; i++) { int bx = cx * 16 + (i & 15), bz = cz * 16 + (i >> 4); top[i] = (short) CodecBench.height(bx, bz, ROUGH); m[i] = CodecBench.material(top[i], bx, bz); }
            pyr.put(new ColumnTile(cx, cz, top, m, Confidence.OBSERVED, 0L));
        }
        if (mode.equals("all")) {
        scenario("walk, low budget", speeds[0], 2, 4000, steps, pyr);
        scenario("walk, high budget", speeds[0], 2, 16000, steps, pyr);
        scenario("elytra, 16 KB/step", speeds[1], 60, 16000, steps, pyr);
        scenario("elytra, 64 KB/step", speeds[1], 60, 64000, steps, pyr);
        }
        if (mode.equals("all") || mode.equals("cull")) cullStudy(pyr);
        if (mode.equals("all") || mode.equals("study")) rayStudy(pyr);
    }
}
