package dev.awre.stream;

/**
 * Camera-relative placement of a tile-local mesh, in double precision. The mesh vertices stay tile-local floats on the GPU; each draw
 * adds one offset (tile origin - camera). Computing that difference in double BEFORE narrowing to float is what keeps terrain stable
 * at x/z = +-29,999,984 where a float world coordinate has a 2-block step and absolute float vertices would visibly jitter.
 * Float precision of the offset itself is ~|offset| * 6e-8 (about 1 mm at the default 16,384-block maximum distance).
 */
public final class CameraRelative {
    private CameraRelative() { }

    /** @return {dx, dy, dz} to add to a tile-local vertex (x, y, z): origin minus camera on x/z, minus camera y on y */
    public static float[] offset(double originX, double originZ, double camX, double camY, double camZ) {
        return new float[]{(float) (originX - camX), (float) -camY, (float) (originZ - camZ)};
    }

    /** The camera-relative position of a tile-local vertex, in double (for tests and diagnostics). */
    public static double[] vertexRelativeToCamera(double originX, double originZ, float localX, float localY, float localZ, double camX, double camY, double camZ) {
        float[] o = offset(originX, originZ, camX, camY, camZ);
        return new double[]{(double) localX + o[0], (double) localY + o[1], (double) localZ + o[2]};
    }
}
