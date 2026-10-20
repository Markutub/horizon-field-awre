package dev.horizonfield.core;

import java.nio.file.Path;
import java.util.*;

/**
 * StreamSim generalised to REAL captured terrain (read from a .hfl via TileLogReader; the file is never modified).
 * Same strategies and metrics as StreamSim (S1..S5, sweep-vs-truth, culling study, ray-count study) so numbers are comparable,
 * BUT: heights/materials are the real captured top surface; unknown chunks neither occlude nor are sent nor count as truth.
 * Ground truth visibility = independent dense ray march (2048 rays, 2-block steps) over the real 2-block height grid.
 * The capture is only ~69x69 chunks, so the sim radius is SCALED: MINR = MAXR/4, rings at MAXR/2 and 3*MAXR/4 (same proportions as
 * StreamSim's 128/256/384/512) and byte budgets are scaled by (MAXR/512)^2 so starvation ratios stay comparable.
 * Viewpoints are restricted to positions whose whole [0,MAXR] disc lies inside the captured bounding box.
 * Usage: java dev.horizonfield.core.RealStreamSim file.hfl [maxR=256] [all|cull|study|sweep]
 */
public class RealStreamSim {
    static double MINR, MAXR; static double[] RING_LO, RING_HI; static double BSCALE; static double HB; static int PATHKIND;
    static final int UNK = -10000;
    static final Map<Long, ColumnTile> M = new HashMap<>();
    static int GX0, GZ0, GW, GH; static short[] grid;
    static int minCx, maxCx, minCz, maxCz;

    static int rh(int bx, int bz) {
        ColumnTile t = M.get(HeightPyramid.key(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16)));
        return t == null ? UNK : t.topAt(Math.floorMod(bx, 16), Math.floorMod(bz, 16));
    }
    static int rmat(int bx, int bz) {
        ColumnTile t = M.get(HeightPyramid.key(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16)));
        return t == null ? 0 : t.materialAt(Math.floorMod(bx, 16), Math.floorMod(bz, 16));
    }
    static int gridH(double x, double z) {
        int gx = ((int) Math.floor(x) - GX0) >> 1, gz = ((int) Math.floor(z) - GZ0) >> 1;
        if (gx < 0 || gz < 0 || gx >= GW || gz >= GH) return UNK;
        return grid[gz * GW + gx];
    }
    static int fl(double v) { return Math.floorDiv((int) Math.floor(v), 16); }
    static long tk(int level, int tx, int tz) { return ((long) level << 58) | ((long) (tx & 0x1FFFFFFF) << 29) | (tz & 0x1FFFFFFFL); }

    record TileData(short[] top, int bytes) { }
    static final TileData NONE = new TileData(null, -1);
    static final Map<Long, TileData> MEMO = new HashMap<>();
    /** Coarse tile built from real heights; null if any sample point is in an unknown chunk (cannot be built/sent). */
    static TileData tile(int level, int tx, int tz) {
        TileData td = MEMO.computeIfAbsent(tk(level, tx, tz), k -> {
            int s = 1 << level; short[] top = new short[256], m = new short[256];
            for (int i = 0; i < 256; i++) {
                int bx = tx * 16 * s + (i & 15) * s, bz = tz * 16 * s + (i >> 4) * s;
                int h = rh(bx, bz); if (h == UNK) return NONE;
                top[i] = (short) h; m[i] = (short) rmat(bx, bz);
            }
            return new TileData(top, TileCodec.encode(new ColumnTile(tx, tz, top, m, Confidence.OBSERVED, 0L)).length);
        });
        return td == NONE ? null : td;
    }

    static Set<Long> truth(double px, double pz, double eyeY) {
        Set<Long> out = new HashSet<>(); int rays = 2048;
        for (int r = 0; r < rays; r++) {
            double a = 2 * Math.PI * r / rays, dx = Math.cos(a), dz = Math.sin(a), maxSlope = -1e9;
            for (double d = 2; d <= MAXR; d += 2) {
                double x = px + dx * d, z = pz + dz * d; int h = gridH(x, z);
                if (h == UNK) continue; // unknown neither occludes nor is truth
                double slope = (h - eyeY) / d;
                if (slope >= maxSlope) {
                    maxSlope = slope;
                    int cx = fl(x), cz = fl(z); double cd = Math.hypot(cx * 16 + 8 - px, cz * 16 + 8 - pz);
                    if (d >= MINR && cd >= MINR && cd <= MAXR) out.add(HeightPyramid.key(cx, cz));
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
    static Set<Long> sweepSet(HeightPyramid pyr, double x, double ey, double z, double slack, double minR, double maxR, int bins) {
        Set<Long> sw = new HashSet<>();
        for (long k : VisibilitySweep.run(pyr, new VisibilityParams(x, ey, z, minR, maxR, bins, slack)).toChunkSet()) {
            int cx = (int) (k >> 32), cz = (int) k; double d = Math.hypot(cx * 16 + 8 - x, cz * 16 + 8 - z);
            if (d >= minR && d <= maxR) sw.add(k);
        }
        return sw;
    }
    static Set<Long> sweepSet(HeightPyramid pyr, double x, double ey, double z, double slack) { return sweepSet(pyr, x, ey, z, slack, MINR, MAXR, 1024); }

    // ---- paths ----
    static double xLo() { return minCx * 16 + MAXR; } static double xHi() { return (maxCx + 1) * 16 - MAXR; }
    static double zLo() { return minCz * 16 + MAXR; } static double zHi() { return (maxCz + 1) * 16 - MAXR; }

    static double pickRow() { // among 5 candidate rows, the one whose x-path has the largest mean |dh| (roughest real terrain)
        double best = -1, bz = (zLo() + zHi()) / 2;
        for (int c = 0; c < 5; c++) {
            double z = zLo() + (zHi() - zLo()) * c / 4.0; long n = 0, s = 0; int prev = UNK;
            for (double x = xLo(); x <= xHi(); x += 4) { int h = rh((int) x, (int) z); if (prev != UNK && h != UNK) { s += Math.abs(h - prev); n++; } prev = h; }
            double r = n == 0 ? 0 : s / (double) n;
            System.out.printf("   candidate row z=%.0f: mean |dh| per 4 blocks = %.2f%n", z, r);
            if (r > best) { best = r; bz = z; }
        }
        return bz;
    }
    record Route(double[] px, double[] pz, double[] ey) { }
    static Route path(double speedChunks, double eyeOff, double row, int maxSteps) {
        double step = speedChunks * 16; int steps = PATHKIND == 1 ? maxSteps : Math.min(maxSteps, (int) ((xHi() - xLo()) / step) + 1);
        double[] px = new double[steps], pz = new double[steps], ey = new double[steps];
        for (int i = 0; i < steps; i++) { px[i] = PATHKIND == 1 ? xLo() + 40 + Math.min(i % 40, 40 - i % 40) * step : xLo() + i * step; pz[i] = row; int h = rh((int) px[i], (int) row); ey[i] = (h == UNK ? 64 : h) + eyeOff; }
        return new Route(px, pz, ey);
    }

    static void scenario(String label, double speed, double eyeOff, int budget0, double row, HeightPyramid pyr) { scenario(label, speed, eyeOff, budget0, row, pyr, 120); }
    static void scenario(String label, double speed, double eyeOff, int budget0, double row, HeightPyramid pyr, int maxSteps) {
        Route p = path(speed, eyeOff, row, maxSteps); int steps = p.px().length; double[] px = p.px(), pz = p.pz(), ey = p.ey();
        int budget = (int) Math.max(1, budget0 * BSCALE);
        List<Set<Long>> truths = new ArrayList<>(), sweeps = new ArrayList<>(); double miss = 0, ratio = 0; int valid = 0;
        for (int i = 0; i < steps; i++) {
            Set<Long> t = truth(px[i], pz[i], ey[i]); Set<Long> sw = sweepSet(pyr, px[i], ey[i], pz[i], 8);
            if (!t.isEmpty()) { int m = 0; for (long k : t) if (!sw.contains(k)) m++; miss += m / (double) t.size(); ratio += sw.size() / (double) t.size(); valid++; }
            truths.add(t); sweeps.add(sw);
        }
        System.out.printf("%n== REAL %s: speed=%.1f chunks/step, eye=ground+%.0f, budget=%d B/step (scaled), %d steps, z=%.0f%n", label, speed, eyeOff, budget, steps, row);
        System.out.printf("   sweep vs truth: false-occlusion = %.2f%%, sweep set / truth set = %.2fx%n", 100 * miss / Math.max(1, valid), ratio / Math.max(1, valid));
        System.out.printf("   %-28s %8s %9s %8s %9s %9s %9s %9s %10s %10s %9s%n", "strategy", "tiles", "KB sent", "useful%", "t90(step)", "cov(all)", "cov(late)", "err(late)", "relevel t%", "relevel KB%", "resendKey");
        String[] names = {"S1 L0 nearest-first", "S2 L0 sweep-ranked", "S3 clipmap nearest", "S4 clipmap sweep-ranked", "S5 clipmap keep-until-repl.", "S5h S5, band HB", "S6 S5h + level hysteresis"};
        for (int mode = 0; mode < 7; mode++) {
            boolean clip = mode >= 2, ranked = mode == 1 || mode == 3, retain = mode >= 4, lvlHyst = mode == 6;
            Map<Long, int[]> cache = new HashMap<>();
            long tiles = 0, bytesTot = 0, useful = 0, relevel = 0, relevelBytes = 0, resendSame = 0; int t90 = -1;
            Map<Long, Integer> lastLevel = new HashMap<>(); Set<Long> everKey = new HashSet<>(); double covAll = 0, covLate = 0, errLate = 0; int lateN = 0, covN = 0;
            for (int i = 0; i < steps; i++) {
                double x = px[i], z = pz[i]; Set<Long> sw = sweeps.get(i), tr = truths.get(i);
                Set<Long> keys = new HashSet<>(cache.keySet());
                final double hyst = mode >= 5 ? HB : 32 * MAXR / 512;
                cache.values().removeIf(c -> {
                    int s = 1 << c[0]; double d = Math.hypot((c[1] + 0.5) * 16 * s - x, (c[2] + 0.5) * 16 * s - z);
                    double lo = clip ? RING_LO[c[0]] : MINR, hi = clip ? RING_HI[c[0]] : MAXR;
                    boolean outside = d < lo - hyst || d > hi + hyst;
                    if (!retain || !outside) return outside;
                    if (d < MINR - hyst || d > MAXR + hyst) return true;
                    int L2 = d < RING_HI[0] ? 0 : d < RING_HI[1] ? 1 : 2;
                    if (L2 > c[0]) return keys.contains(tk(L2, (c[1] * s) >> L2, (c[2] * s) >> L2));
                    int f = 1 << (c[0] - L2);
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
                        if (tile(L, tx, tz) == null) continue; // unknown data: cannot be sent
                        if (lvlHyst && covered(cache, L, tx, tz, x, z, hyst)) continue;
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
                if (tr.isEmpty()) continue;
                int covered = 0; double err = 0;
                for (long k : tr) {
                    int cx = (int) (k >> 32), cz = (int) k;
                    for (int L = 0; L <= 2; L++) {
                        if (!cache.containsKey(tk(L, cx >> L, cz >> L))) continue;
                        covered++; int s = 1 << L; TileData td = tile(L, cx >> L, cz >> L); double e4 = 0;
                        int[][] offs = {{3, 3}, {11, 3}, {3, 11}, {11, 11}};
                        for (int[] o : offs) {
                            int bx = cx * 16 + o[0], bz = cz * 16 + o[1];
                            int lx = Math.max(0, Math.min(15, (bx - (cx >> L) * 16 * s) / s)), lz = Math.max(0, Math.min(15, (bz - (cz >> L) * 16 * s) / s));
                            e4 += Math.abs(td.top()[lz * 16 + lx] - rh(bx, bz));
                        }
                        err += e4 / 4; break;
                    }
                }
                double cov = covered / (double) tr.size(); covAll += cov; covN++;
                if (t90 < 0 && cov >= 0.9) t90 = i;
                if (i >= steps / 2) { covLate += cov; errLate += covered == 0 ? 0 : err / covered; lateN++; }
            }
            System.out.printf("   %-28s %8d %9.1f %7.1f%% %9s %8.1f%% %8.1f%% %8.2f %9.1f%% %9.1f%% %9d%n", names[mode], tiles, bytesTot / 1024.0,
                    100.0 * useful / Math.max(1, tiles), t90 < 0 ? ">" + steps : String.valueOf(t90), 100 * covAll / Math.max(1, covN), 100 * covLate / Math.max(1, lateN), errLate / Math.max(1, lateN),
                    100.0 * relevel / Math.max(1, tiles), 100.0 * relevelBytes / Math.max(1, bytesTot), resendSame);
        }
    }

    /** S6: true if this area is covered by cached tile(s) at ANOTHER level that are still inside THEIR OWN ring band (ring +/- band). Tiles kept only by keep-until-replaced (outside their band) do not count, otherwise they would never be replaced. */
    static boolean covered(Map<Long, int[]> cache, int L, int tx, int tz, double x, double z, double band) {
        for (int L2 = 0; L2 <= 2; L2++) {
            if (L2 == L) continue;
            if (L2 > L) { int a = (tx << L) >> L2, b = (tz << L) >> L2; if (cache.containsKey(tk(L2, a, b)) && inBand(L2, a, b, x, z, band)) return true; }
            else { int f = 1 << (L - L2); boolean all = true;
                for (int dz = 0; dz < f && all; dz++) for (int dx = 0; dx < f; dx++) { int cx = tx * f + dx, cz = tz * f + dz; if (!cache.containsKey(tk(L2, cx, cz)) || !inBand(L2, cx, cz, x, z, band)) { all = false; break; } }
                if (all) return true; }
        }
        return false;
    }
    static boolean inBand(int L, int tx, int tz, double x, double z, double band) {
        int s = 1 << L; double d = Math.hypot((tx + 0.5) * 16 * s - x, (tz + 0.5) * 16 * s - z);
        return d >= RING_LO[L] - band && d <= RING_HI[L] + band;
    }

    static double[] runL0(List<Set<Long>> truths, double[] px, double[] pz, List<Set<Long>> sweepUsed, int steps, int budget) {
        Map<Long, int[]> cache = new HashMap<>(); long tiles = 0, bytes = 0; int t90 = -1, lateN = 0;
        double covLate = 0, minLate = 1, holes = 0; final double hyst = 32 * MAXR / 512;
        for (int i = 0; i < steps; i++) {
            double x = px[i], z = pz[i]; Set<Long> tr = truths.get(i);
            cache.values().removeIf(c -> { double d = Math.hypot((c[1] + 0.5) * 16 - x, (c[2] + 0.5) * 16 - z); return d < MINR - hyst || d > MAXR + hyst; });
            List<int[]> cands = new ArrayList<>(); List<Double> dist = new ArrayList<>();
            for (int tz = fl(z - MAXR); tz <= fl(z + MAXR); tz++) for (int tx = fl(x - MAXR); tx <= fl(x + MAXR); tx++) {
                double d = Math.hypot((tx + 0.5) * 16 - x, (tz + 0.5) * 16 - z);
                if (d < MINR || d >= MAXR || cache.containsKey(tk(0, tx, tz)) || tile(0, tx, tz) == null) continue;
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
            if (tr.isEmpty()) continue;
            int covered = 0; for (long k : tr) if (cache.containsKey(tk(0, (int) (k >> 32), (int) k))) covered++;
            double cov = covered / (double) tr.size();
            if (t90 < 0 && cov >= 0.9) t90 = i;
            if (i >= steps / 2) { covLate += cov; minLate = Math.min(minLate, cov); holes += tr.size() - covered; lateN++; }
        }
        lateN = Math.max(1, lateN);
        return new double[]{tiles, bytes / 1024.0, t90, 100 * covLate / lateN, 100 * minLate, holes / lateN};
    }

    static void cullStudy(HeightPyramid pyr, double row) {
        double[][] sc = {{0.3, 2, 16000}, {1.5, 60, 64000}}; String[] names = {"walk", "fast/elevated"};
        for (int q = 0; q < 2; q++) {
            double speed = sc[q][0], off = sc[q][1]; int budget = (int) Math.max(1, sc[q][2] * BSCALE);
            Route p = path(speed, off, row, 120); int steps = p.px().length; double[] px = p.px(), pz = p.pz(), ey = p.ey();
            List<Set<Long>> truths = new ArrayList<>(); for (int i = 0; i < steps; i++) truths.add(truth(px[i], pz[i], ey[i]));
            double[] base = runL0(truths, px, pz, null, steps, budget);
            System.out.printf("%n== REAL CULL study, %s, %d B/step, %d steps (L0 nearest-first; candidates restricted to the last sweep's visible set)%n", names[q], budget, steps);
            System.out.printf("   %-30s %8s %9s %8s %8s %9s %9s %11s%n", "variant", "tiles", "KB sent", "KB save", "t90", "cov(late)", "cov(min)", "holes/step");
            System.out.printf("   %-30s %8.0f %9.1f %8s %8.0f %8.1f%% %8.1f%% %11.1f%n", "no culling (baseline)", base[0], base[1], "-", base[2], base[3], base[4], base[5]);
            for (double slack : new double[]{8, 32}) {
                List<Set<Long>> sweepAt = new ArrayList<>(); for (int i = 0; i < steps; i++) sweepAt.add(sweepSet(pyr, px[i], ey[i], pz[i], slack));
                for (int K : new int[]{1, 4, 10}) {
                    List<Set<Long>> used = new ArrayList<>(); for (int i = 0; i < steps; i++) used.add(sweepAt.get((i / K) * K));
                    double[] r = runL0(truths, px, pz, used, steps, budget);
                    System.out.printf("   %-30s %8.0f %9.1f %7.1f%% %8s %8.1f%% %8.1f%% %11.1f%n", "slack=" + (int) slack + " sweep every " + K + " step" + (K > 1 ? "s" : ""),
                            r[0], r[1], 100 * (1 - r[1] / base[1]), r[2] < 0 ? ">" + steps : String.valueOf((int) r[2]), r[3], r[4], r[5]);
                }
            }
            for (int K : new int[]{4, 10}) {
                double pad = speed * 16 * K; List<Set<Long>> used = new ArrayList<>(); Set<Long> cur = null;
                for (int i = 0; i < steps; i++) { if (i % K == 0) cur = sweepSet(pyr, px[i], ey[i], pz[i], 8, Math.max(16, MINR - pad), MAXR + pad, 1024); used.add(cur); }
                double[] r = runL0(truths, px, pz, used, steps, budget);
                System.out.printf("   %-30s %8.0f %9.1f %7.1f%% %8s %8.1f%% %8.1f%% %11.1f%n", "slack=8 PADDED, every " + K + " steps",
                        r[0], r[1], 100 * (1 - r[1] / base[1]), r[2] < 0 ? ">" + steps : String.valueOf((int) r[2]), r[3], r[4], r[5]);
            }
        }
    }

    /** Sweep accuracy on a grid of viewpoints over the valid region, several eye heights and ray counts. */
    static void rayStudy(HeightPyramid pyr) {
        List<double[]> pts = new ArrayList<>();
        int nx = 7, nz = 7;
        for (int a = 0; a < nx; a++) for (int b = 0; b < nz; b++) pts.add(new double[]{xLo() + (xHi() - xLo()) * a / (nx - 1.0), zLo() + (zHi() - zLo()) * b / (nz - 1.0)});
        System.out.printf("%n== REAL sweep false-occlusion vs azimuth rays (%d viewpoints on a grid over the valid region)%n", pts.size());
        for (double off : new double[]{2, 60}) for (int bins : new int[]{1024, 2048, 4096}) {
            double miss = 0, ratio = 0, worst = 0; int n = 0;
            for (double[] q : pts) {
                int h = rh((int) q[0], (int) q[1]); double ey = (h == UNK ? 64 : h) + off;
                Set<Long> t = truth(q[0], q[1], ey); if (t.isEmpty()) continue;
                Set<Long> sw = sweepSet(pyr, q[0], ey, q[1], 8, MINR, MAXR, bins);
                int m = 0; for (long k : t) if (!sw.contains(k)) m++;
                double mm = m / (double) t.size(); miss += mm; worst = Math.max(worst, mm); ratio += sw.size() / (double) t.size(); n++;
            }
            System.out.printf("   eye=ground+%-3.0f bins=%4d  mean miss=%.2f%% worst viewpoint=%.2f%%  sweep/truth=%.2fx  (n=%d)%n", off, bins, 100 * miss / Math.max(1, n), 100 * worst, ratio / Math.max(1, n), n);
        }
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 1) { System.err.println("usage: RealStreamSim <file.hfl> [maxR=256] [all|cull|study|sweep|hyst] [bandHB=32*maxR/256]"); System.exit(2); }
        MAXR = a.length > 1 ? Double.parseDouble(a[1]) : 256; String mode = a.length > 2 ? a[2] : "all";
        HB = a.length > 3 ? Double.parseDouble(a[3]) : 32 * MAXR / 256; MINR = MAXR / 4; RING_LO = new double[]{MINR, MAXR / 2, MAXR * 0.75}; RING_HI = new double[]{MAXR / 2, MAXR * 0.75, MAXR + 1}; BSCALE = (MAXR / 512) * (MAXR / 512);
        TileLogReader.Result r = TileLogReader.read(Path.of(a[0]), t -> M.put(HeightPyramid.key(t.chunkX(), t.chunkZ()), t));
        minCx = minCz = Integer.MAX_VALUE; maxCx = maxCz = Integer.MIN_VALUE;
        for (ColumnTile t : M.values()) { minCx = Math.min(minCx, t.chunkX()); maxCx = Math.max(maxCx, t.chunkX()); minCz = Math.min(minCz, t.chunkZ()); maxCz = Math.max(maxCz, t.chunkZ()); }
        System.out.printf("file=%s tiles=%d extent chunks x[%d..%d] z[%d..%d] MAXR=%.0f MINR=%.0f rings L0[%.0f,%.0f) L1[%.0f,%.0f) L2[%.0f,%.0f] budget scale=%.3f%n",
                a[0], M.size(), minCx, maxCx, minCz, maxCz, MAXR, MINR, RING_LO[0], RING_HI[0], RING_LO[1], RING_HI[1], RING_LO[2], MAXR, BSCALE);
        System.out.printf("valid viewpoint region: x[%.0f..%.0f] z[%.0f..%.0f]%n", xLo(), xHi(), zLo(), zHi());
        if (xHi() <= xLo() || zHi() <= zLo()) { System.out.println("capture too small for this MAXR"); return; }
        GX0 = minCx * 16; GZ0 = minCz * 16; GW = (maxCx - minCx + 1) * 16 / 2; GH = (maxCz - minCz + 1) * 16 / 2; grid = new short[GW * GH];
        for (int gz = 0; gz < GH; gz++) for (int gx = 0; gx < GW; gx++) grid[gz * GW + gx] = (short) rh(GX0 + gx * 2, GZ0 + gz * 2);
        HeightPyramid pyr = new HeightPyramid(6); for (ColumnTile t : M.values()) pyr.put(t);
        System.out.println("path row selection:"); double row = pickRow();
        if (mode.equals("all")) {
            scenario("walk, low budget", 0.3, 2, 4000, row, pyr);
            scenario("walk, high budget", 0.3, 2, 16000, row, pyr);
            scenario("fast+elevated, 16 KB/step", 1.5, 60, 16000, row, pyr);
            scenario("fast+elevated, 64 KB/step", 1.5, 60, 64000, row, pyr);
        }
        if (mode.equals("all") || mode.equals("hyst")) {
            PATHKIND = 1; System.out.printf("%n#### back-and-forth path (20 steps out, 20 back, 100 steps), S5h/S6 band HB=%.0f blocks ####%n", HB);
            scenario("back-and-forth, low budget", 0.3, 2, 4000, row, pyr, 100);
            scenario("back-and-forth, high budget", 0.3, 2, 16000, row, pyr, 100);
            PATHKIND = 0;
        }
        if (mode.equals("all") || mode.equals("cull")) cullStudy(pyr, row);
        if (mode.equals("all") || mode.equals("study") || mode.equals("sweep")) rayStudy(pyr);
    }
}
