package dev.horizonfield.core.terrain;

import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * Turns one {@link LodTile} into a triangle mesh of "stepped columns": each sample is a flat top at (top + 1), neighbours at different
 * heights are joined by vertical walls, and every border towards missing data or another tile gets a downward SKIRT that hides cracks
 * between tiles of different levels (it never looks at neighbour tiles, so tiles can be meshed independently and cached).
 * Flat tops of equal height and colour are merged greedily into rectangles, walls are merged along their edge.
 * Pure and deterministic. World coordinates: x = (tx*16 + sx) * 2^lod, y = height, z likewise.
 */
public final class TerrainMesher {
    public enum Face { UP, NORTH, SOUTH, WEST, EAST, SKIRT }

    /** Triangles with one position, ARGB colour and face tag per vertex. Winding is counter-clockwise seen from outside. */
    public static final class Mesh {
        public final float[] positions;   // x,y,z per vertex
        public final int[] colors;        // ARGB per vertex
        public final byte[] faces;        // Face ordinal per vertex
        public final int[] indices;       // 3 per triangle

        Mesh(float[] p, int[] c, byte[] f, int[] i) { positions = p; colors = c; faces = f; indices = i; }

        public int vertexCount() { return colors.length; }
        public int triangleCount() { return indices.length / 3; }

        /** Total triangle area of one face kind. */
        public double area(Face face) {
            double sum = 0;
            for (int t = 0; t < indices.length; t += 3) {
                if (faces[indices[t]] != face.ordinal()) continue;
                double[] n = normal(t);
                sum += 0.5 * Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            }
            return sum;
        }

        /** Un-normalised normal (cross product) of triangle starting at index offset t. */
        public double[] normal(int t) {
            int a = indices[t] * 3, b = indices[t + 1] * 3, c = indices[t + 2] * 3;
            double ux = positions[b] - positions[a], uy = positions[b + 1] - positions[a + 1], uz = positions[b + 2] - positions[a + 2];
            double vx = positions[c] - positions[a], vy = positions[c + 1] - positions[a + 1], vz = positions[c + 2] - positions[a + 2];
            return new double[]{uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
        }
    }

    private static final class Builder {
        float[] pos = new float[256 * 3]; int[] col = new int[256]; byte[] face = new byte[256]; int[] idx = new int[384]; int nv, ni;

        void quad(Face f, int color, double x0, double y0, double z0, double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3) {
            if (nv + 4 > col.length) { int n = col.length * 2; pos = Arrays.copyOf(pos, n * 3); col = Arrays.copyOf(col, n); face = Arrays.copyOf(face, n); }
            if (ni + 6 > idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
            double[][] v = {{x0, y0, z0}, {x1, y1, z1}, {x2, y2, z2}, {x3, y3, z3}};
            for (int i = 0; i < 4; i++) { pos[(nv + i) * 3] = (float) v[i][0]; pos[(nv + i) * 3 + 1] = (float) v[i][1]; pos[(nv + i) * 3 + 2] = (float) v[i][2]; col[nv + i] = color; face[nv + i] = (byte) f.ordinal(); }
            idx[ni++] = nv; idx[ni++] = nv + 1; idx[ni++] = nv + 2; idx[ni++] = nv; idx[ni++] = nv + 2; idx[ni++] = nv + 3;
            nv += 4;
        }

        Mesh build() { return new Mesh(Arrays.copyOf(pos, nv * 3), Arrays.copyOf(col, nv), Arrays.copyOf(face, nv), Arrays.copyOf(idx, ni)); }
    }

    private TerrainMesher() { }

    /** @param paletteKey material id -> block key (e.g. the client's palette); @param skirtDepth blocks the skirts hang down */
    public static Mesh mesh(LodTile t, IntFunction<String> paletteKey, double skirtDepth) {
        final int n = LodTile.SIDE;
        final double cell = 1 << t.key().lod();
        final double ox = (double) t.key().tx() * n * cell, oz = (double) t.key().tz() * n * cell;
        int[] top = new int[n * n], color = new int[n * n];
        boolean[] valid = new boolean[n * n];
        java.util.HashMap<Integer, Integer> cache = new java.util.HashMap<>();
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            int i = z * n + x, h = t.topAt(x, z);
            valid[i] = h != LodTile.NO_DATA;
            if (!valid[i]) continue;
            top[i] = h;
            int id = t.materialAt(x, z);
            color[i] = cache.computeIfAbsent(id, k -> { int c = BlockColors.argb(paletteKey.apply(k)); return c == 0 ? 0xFF808080 : c; });
        }
        Builder b = new Builder();

        // tops: greedy rectangles of equal height and colour
        boolean[] used = new boolean[n * n];
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            int i = z * n + x;
            if (!valid[i] || used[i]) continue;
            int w = 1;
            while (x + w < n && same(valid, top, color, used, i, z * n + x + w)) w++;
            int d = 1;
            outer:
            while (z + d < n) {
                for (int k = 0; k < w; k++) if (!same(valid, top, color, used, i, (z + d) * n + x + k)) break outer;
                d++;
            }
            for (int dz = 0; dz < d; dz++) for (int dx = 0; dx < w; dx++) used[(z + dz) * n + x + dx] = true;
            double y = top[i] + 1;
            double x0 = ox + x * cell, x1 = ox + (x + w) * cell, z0 = oz + z * cell, z1 = oz + (z + d) * cell;
            b.quad(Face.UP, color[i], x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0);          // CCW seen from above (+y)
        }

        // walls and skirts: for each cell and direction, merge runs along the edge
        for (int dir = 0; dir < 4; dir++) {
            boolean alongX = dir < 2;                      // 0 NORTH (z-1), 1 SOUTH (z+1) run along x; 2 WEST (x-1), 3 EAST (x+1) run along z
            for (int line = 0; line < n; line++) {
                int run = 0;
                while (run < n) {
                    int sx = alongX ? run : line, sz = alongX ? line : run;
                    int[] w = wallAt(valid, top, n, sx, sz, dir);
                    if (w == null) { run++; continue; }
                    int len = 1;
                    while (run + len < n) {
                        int nx = alongX ? run + len : line, nz = alongX ? line : run + len;
                        int[] w2 = wallAt(valid, top, n, nx, nz, dir);
                        if (w2 == null || w2[0] != w[0] || w2[1] != w[1] || w2[2] != w[2] || color[nz * n + nx] != color[sz * n + sx]) break;
                        len++;
                    }
                    emitWall(b, dir, alongX, sx, sz, len, w, color[sz * n + sx], ox, oz, cell, skirtDepth);
                    run += len;
                }
            }
        }
        return b.build();
    }

    private static boolean same(boolean[] valid, int[] top, int[] color, boolean[] used, int a, int b) {
        return valid[b] && !used[b] && top[b] == top[a] && color[b] == color[a];
    }

    /** {lowY, highY, kind} of the wall of cell (x,z) towards dir, or null. kind 0 = wall to a lower neighbour, 1 = skirt. Y values are block tops + 1. */
    private static int[] wallAt(boolean[] valid, int[] top, int n, int x, int z, int dir) {
        int i = z * n + x;
        if (!valid[i]) return null;
        int nx = x + (dir == 2 ? -1 : dir == 3 ? 1 : 0), nz = z + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
        boolean outside = nx < 0 || nz < 0 || nx >= n || nz >= n;
        if (outside || !valid[nz * n + nx]) return new int[]{0, top[i] + 1, 1};
        int nt = top[nz * n + nx];
        return nt < top[i] ? new int[]{nt + 1, top[i] + 1, 0} : null;
    }

    private static void emitWall(Builder b, int dir, boolean alongX, int sx, int sz, int len, int[] w, int color, double ox, double oz, double cell, double skirtDepth) {
        boolean skirt = w[2] == 1;
        double yHigh = w[1], yLow = skirt ? w[1] - skirtDepth : w[0];
        Face f = skirt ? Face.SKIRT : Face.values()[1 + dir];
        double a0, a1;                                      // extent along the run
        double fixed;                                       // the coordinate of the edge plane
        if (alongX) {
            a0 = ox + sx * cell; a1 = ox + (sx + len) * cell;
            fixed = oz + (dir == 0 ? sz : sz + 1) * cell;
            if (dir == 0) b.quad(f, color, a0, yHigh, fixed, a1, yHigh, fixed, a1, yLow, fixed, a0, yLow, fixed);   // faces -z
            else b.quad(f, color, a1, yHigh, fixed, a0, yHigh, fixed, a0, yLow, fixed, a1, yLow, fixed);            // faces +z
        } else {
            a0 = oz + sz * cell; a1 = oz + (sz + len) * cell;
            fixed = ox + (dir == 2 ? sx : sx + 1) * cell;
            if (dir == 2) b.quad(f, color, fixed, yHigh, a1, fixed, yHigh, a0, fixed, yLow, a0, fixed, yLow, a1);   // faces -x
            else b.quad(f, color, fixed, yHigh, a0, fixed, yHigh, a1, fixed, yLow, a1, fixed, yLow, a0);            // faces +x
        }
    }
}
