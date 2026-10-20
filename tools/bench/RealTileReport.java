package dev.horizonfield.core;

import java.nio.file.Path;
import java.util.*;

/**
 * Offline report on a HorizonField cache (.hfl) copied from a Paper server (read-only; the file is never modified).
 * Usage: bash tools/run-report.sh path/to/<world-uuid>.hfl
 * Reports: file health, extent/coverage, heights, materials, REAL LOD error for 2/4/8-chunk coarse tiles,
 * and encoded bytes per tile (v1 per-tile Deflate vs TileCodec). Surface top-heights only (no caves/overhangs).
 */
public class RealTileReport {
    static int h(Map<Long, ColumnTile> m, int bx, int bz) {
        ColumnTile t = m.get(HeightPyramid.key(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16)));
        return t == null ? Integer.MIN_VALUE : t.topAt(Math.floorMod(bx, 16), Math.floorMod(bz, 16));
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 1) { System.err.println("usage: RealTileReport <file.hfl>"); System.exit(2); }
        Map<Long, ColumnTile> m = new HashMap<>();
        TileLogReader.Result r = TileLogReader.read(Path.of(a[0]), t -> m.put(HeightPyramid.key(t.chunkX(), t.chunkZ()), t));
        System.out.printf("file: %s%nfingerprint: %s (format v%d)%n", a[0], r.fingerprint(), r.formatVersion());
        System.out.printf("records=%d distinct chunks=%d  good bytes=%d / %d (discarded tail %d)%n", r.records(), m.size(), r.goodBytes(), r.totalBytes(), r.discardedBytes());
        if (m.isEmpty()) { System.out.println("no tiles: nothing to report"); return; }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        Map<Confidence, Integer> conf = new EnumMap<>(Confidence.class);
        long cols = 0, sumTop = 0, rough = 0, roughN = 0; int mnTop = Integer.MAX_VALUE, mxTop = Integer.MIN_VALUE;
        Map<Integer, Long> matCols = new TreeMap<>();
        for (ColumnTile t : m.values()) {
            minX = Math.min(minX, t.chunkX()); maxX = Math.max(maxX, t.chunkX()); minZ = Math.min(minZ, t.chunkZ()); maxZ = Math.max(maxZ, t.chunkZ());
            conf.merge(t.confidence(), 1, Integer::sum);
            for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int y = t.topAt(lx, lz); cols++; sumTop += y; mnTop = Math.min(mnTop, y); mxTop = Math.max(mxTop, y);
                matCols.merge(t.materialAt(lx, lz), 1L, Long::sum);
                if (lx < 15) { rough += Math.abs(y - t.topAt(lx + 1, lz)); roughN++; }
            }
        }
        long box = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        System.out.printf("%nextent: chunks x[%d..%d] z[%d..%d] = %d x %d, known %.1f%% of the bounding box%n", minX, maxX, minZ, maxZ, maxX - minX + 1, maxZ - minZ + 1, 100.0 * m.size() / box);
        System.out.println("confidence: " + conf);
        System.out.printf("heights: min=%d max=%d mean=%.1f  mean |dY| between adjacent columns=%.2f blocks%n", mnTop, mxTop, sumTop / (double) cols, rough / (double) roughN);
        final long totalCols = cols;
        System.out.println("top materials (by columns):");
        matCols.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(8)
                .forEach(e -> System.out.printf("   %-28s %5.1f%%%n", r.palette().keyOf(e.getKey()), 100.0 * e.getValue() / totalCols));

        System.out.printf("%nLOD error measured on the tiles in this file (coarse tile = 16x16 samples over 2^L x 2^L chunks, sample every 2^L blocks, vs full-res top height):%n");
        for (int L = 1; L <= 3; L++) {
            int s = 1 << L; long n = 0, sum = 0, zero = 0; int max = 0; long[] hist = new long[65]; int groups = 0;
            for (long k : m.keySet()) {
                int cx = (int) (k >> 32), cz = (int) k;
                if (Math.floorMod(cx, s) != 0 || Math.floorMod(cz, s) != 0) continue;
                boolean full = true;
                for (int dz = 0; dz < s && full; dz++) for (int dx = 0; dx < s; dx++) if (!m.containsKey(HeightPyramid.key(cx + dx, cz + dz))) { full = false; break; }
                if (!full) continue;
                groups++;
                for (int dz = 0; dz < 16 * s; dz++) for (int dx = 0; dx < 16 * s; dx++) {
                    int bx = cx * 16 + dx, bz = cz * 16 + dz;
                    int e = Math.abs(h(m, bx, bz) - h(m, cx * 16 + (dx / s) * s, cz * 16 + (dz / s) * s));
                    n++; sum += e; if (e == 0) zero++; max = Math.max(max, e); hist[Math.min(e, 64)]++;
                }
            }
            if (n == 0) { System.out.printf("   L%d (%d-chunk tiles): no complete aligned group%n", L, s); continue; }
            int p95 = -1, p99 = -1;
            long acc = 0; for (int e = 0; e <= 64; e++) { acc += hist[e]; if (p95 < 0 && acc >= 0.95 * n) p95 = e; if (p99 < 0 && acc >= 0.99 * n) p99 = e; }
            System.out.printf("   L%d (%d-chunk tiles, %d groups): mean=%.2f  exact=%.1f%%  p95=%d  p99=%d  max=%d blocks%n", L, s, groups, sum / (double) n, 100.0 * zero / n, p95, p99, max);
        }

        long v1 = 0, raw = 0; java.io.ByteArrayOutputStream codecBatch = new java.io.ByteArrayOutputStream(), shortsBatch = new java.io.ByteArrayOutputStream();
        for (ColumnTile t : m.values()) {
            v1 += TileLog.encodeTile(t).length; byte[] e = TileCodec.encode(t); raw += e.length; codecBatch.write(e);
            for (short y : t.topArray()) { shortsBatch.write(y >> 8); shortsBatch.write(y); }
            for (short y : t.materialArray()) { shortsBatch.write(y >> 8); shortsBatch.write(y); }
        }
        int n = m.size();
        System.out.printf("%nencoded bytes per tile (%d tiles in this file): v1 per-tile deflate=%.1f  TileCodec raw=%.1f  TileCodec+batch deflate=%.1f  raw shorts+batch deflate=%.1f%n",
                n, v1 / (double) n, raw / (double) n, CodecBench.deflated(codecBatch.toByteArray()) / (double) n, CodecBench.deflated(shortsBatch.toByteArray()) / (double) n);
    }
}
