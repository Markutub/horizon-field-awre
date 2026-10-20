package dev.horizonfield.core;

import java.util.zip.Deflater;

/** SYNTHETIC size benchmark (fractal noise, height-banded materials). Not a Minecraft measurement.
 *  Compares TileLog.encodeTile (v1: Deflate of 2x256 shorts) against TileCodec (palette+bitpack+base/delta). */
public class CodecBench {
    static double hash(int x, int z, long s) {
        long h = x * 374761393L + z * 668265263L + s * 1442695040888963407L;
        h = (h ^ (h >>> 13)) * 1274126177L; h ^= h >>> 16; return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }
    static double noise(double x, double z, long s) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0; fx = fx*fx*(3-2*fx); fz = fz*fz*(3-2*fz);
        double a = hash(x0,z0,s), b = hash(x0+1,z0,s), c = hash(x0,z0+1,s), d = hash(x0+1,z0+1,s);
        return a + (b-a)*fx + (c-a)*fz + (a-b-c+d)*fx*fz;
    }
    static int height(int bx, int bz, double rough) {
        double h = 0, amp = 1, f = 1.0/256, sum = 0;
        for (int o = 0; o < 5; o++) { h += amp * noise(bx*f, bz*f, 7+o); sum += amp; amp *= 0.5; f *= 2; }
        return (int) (50 + (h/sum) * rough);
    }
    static short material(int y, int bx, int bz) {
        if (y < 62) return 3;                       // sand/gravel below sea level
        if (y > 150) return (short) (y > 190 ? 5 : 4); // stone / snow
        return (short) (hash(bx, bz, 99) > 0.93 ? 6 : 2); // grass with occasional podzol-like patch
    }
    static int deflated(byte[] all) {
        Deflater df = new Deflater(Deflater.BEST_SPEED); df.setInput(all); df.finish();
        byte[] buf = new byte[all.length + 64]; int n = 0; while (!df.finished()) n += df.deflate(buf); df.end(); return n;
    }
    public static void main(String[] args) throws Exception {
        for (double rough : new double[]{10, 40, 120, 220}) {
            long v1 = 0, v2 = 0; int tiles = 0;
            java.io.ByteArrayOutputStream batch = new java.io.ByteArrayOutputStream(), rawBatch = new java.io.ByteArrayOutputStream();
            long t1 = 0, t2 = 0;
            for (int cz = -20; cz < 20; cz++) for (int cx = -20; cx < 20; cx++) {
                short[] top = new short[256], m = new short[256];
                for (int i = 0; i < 256; i++) {
                    int bx = cx*16 + (i & 15), bz = cz*16 + (i >> 4);
                    top[i] = (short) height(bx, bz, rough); m[i] = material(top[i], bx, bz);
                }
                ColumnTile t = new ColumnTile(cx, cz, top, m, Confidence.OBSERVED, 0L);
                long a = System.nanoTime(); byte[] e1 = TileLog.encodeTile(t); t1 += System.nanoTime() - a;
                a = System.nanoTime(); byte[] e2 = TileCodec.encode(t); t2 += System.nanoTime() - a;
                if (!TileCodec.same(t, TileCodec.decode(e2))) throw new AssertionError("roundtrip");
                v1 += e1.length; v2 += e2.length; tiles++; batch.write(e2);
                for (short x : top) { rawBatch.write(x >> 8); rawBatch.write(x); }
                for (short x : m) { rawBatch.write(x >> 8); rawBatch.write(x); }
            }
            int batchDef = deflated(batch.toByteArray()); int rawDef = deflated(rawBatch.toByteArray());
            System.out.printf("rough=%3.0f tiles=%d  v1(deflate shorts)=%.1f B/tile  codec(raw)=%.1f B/tile  codec+batch-deflate=%.1f B/tile  baseline raw-shorts+batch-deflate=%.1f B/tile  raw ratio=%.2fx  enc us/tile v1=%.1f codec=%.1f%n",
                    rough, tiles, v1/(double)tiles, v2/(double)tiles, batchDef/(double)tiles, rawDef/(double)tiles, v1/(double)v2, t1/1000.0/tiles, t2/1000.0/tiles);
        }
    }
}
