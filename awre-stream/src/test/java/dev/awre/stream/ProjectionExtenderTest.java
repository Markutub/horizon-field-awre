package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.awre.stream.ProjectionExtender.Z;
import org.junit.jupiter.api.Test;

class ProjectionExtenderTest {
    private static void near(double want, double got, double tol, String msg) { assertTrue(Math.abs(want - got) <= tol, msg + ": want " + want + " got " + got + " tol " + tol); }
    private static void near(double want, double got, double tol) { near(want, got, tol, "near"); }
    private static final float N = 0.05f;
    private static float m22(float n, float f) { return -(f + n) / (f - n); }
    private static float m32(float n, float f) { return -2f * f * n / (f - n); }

    @Test void extendingAStandardMatrixEqualsBuildingItDirectlyWithTheNewFar() {
        for (float f1 : new float[] {192f, 768f, 1024f}) for (float f2 : new float[] {2000f, 16384f, 18000f}) {
            Z z = ProjectionExtender.extendFar(m22(N, f1), m32(N, f1), -1f, N, f2);
            assertTrue(z != null, f1 + "->" + f2);
            near(m22(N, f2), z.m22(), 1e-6);
            near(m32(N, f2), z.m32(), 1e-6 * Math.abs(m32(N, f2)) + 1e-9);
            near(-1, ProjectionExtender.ndcDepth(z, N), 1e-3, "near plane -> -1");
            near(1, ProjectionExtender.ndcDepth(z, f2), 1e-3, "far plane -> +1");
        }
    }

    @Test void theNewFarKeepsTerrainInsideTheOldClipOutsideTheOldFarVisible() {
        float f1 = 768f, f2 = 18000f;
        Z old = new Z(m22(N, f1), m32(N, f1)), ext = ProjectionExtender.extendFar(old.m22(), old.m32(), -1f, N, f2);
        assertTrue(ProjectionExtender.ndcDepth(old, 5000f) > 1f, "vanilla clips a tile 5000 blocks away");
        assertTrue(ProjectionExtender.ndcDepth(ext, 5000f) < 1f, "the extended projection does not");
        assertTrue(ProjectionExtender.ndcDepth(ext, 300f) < ProjectionExtender.ndcDepth(ext, 5000f), "depth stays monotonic");
    }

    @Test void nonStandardOrDegenerateInputsAreRefusedNotGuessed() {
        float f1 = 768f;
        // zero-to-one depth range: m22 = f/(n-f), m32 = f*n/(n-f)
        assertEquals(null, ProjectionExtender.extendFar(f1 / (N - f1), f1 * N / (N - f1), -1f, N, 18000f), "zero-to-one depth");
        assertEquals(null, ProjectionExtender.extendFar(m22(N, f1), m32(N, f1), 1f, N, 18000f), "wrong divide");
        assertEquals(null, ProjectionExtender.extendFar(m22(0.1f, f1), m32(0.1f, f1), -1f, N, 18000f), "matrix was built with another near plane");
        assertEquals(null, ProjectionExtender.extendFar(m22(N, f1), m32(N, f1), -1f, 0f, 18000f));
        assertEquals(null, ProjectionExtender.extendFar(m22(N, f1), m32(N, f1), -1f, N, Float.NaN));
        assertEquals(null, ProjectionExtender.extendFar(m22(N, f1), m32(N, f1), -1f, N, 0.01f));
        assertEquals(null, ProjectionExtender.extendFar(Float.NaN, m32(N, f1), -1f, N, 18000f));
    }

    @Test void theImpliedNearIsRecoveredFromAnyVanillaFar() {
        for (float f : new float[] {192f, 768f, 4096f}) near(N, ProjectionExtender.impliedNear(m22(N, f), m32(N, f)), 1e-3 * N, "far " + f);
        // a matrix whose far differs from what the caller believes is still extended correctly: only near matters
        Z z = ProjectionExtender.extendFar(m22(N, 500f), m32(N, 500f), -1f, N, 18000f);
        near(m22(N, 18000f), z.m22(), 1e-6);
    }

    @Test void hugeRequestsAreCappedAtMaxFar() {
        Z z = ProjectionExtender.extendFar(m22(N, 768f), m32(N, 768f), -1f, N, 1e9f);
        near(m22(N, ProjectionExtender.MAX_FAR), z.m22(), 1e-6);
    }

    @Test void chooseFarCoversTheStreamedDistanceAndNeverShrinksBelowVanilla() {
        assertEquals(768f, ProjectionExtender.chooseFar(100, 768f));
        near(16384 * 1.1 + 512, ProjectionExtender.chooseFar(16384, 768f), 1.0);
        assertEquals(ProjectionExtender.MAX_FAR, ProjectionExtender.chooseFar(1e9, 768f));
    }

    @Test void farSettingParsing() {
        assertTrue(Float.isNaN(ProjectionExtender.parseFarSetting(null)));
        assertTrue(Float.isNaN(ProjectionExtender.parseFarSetting("auto")));
        assertTrue(Float.isNaN(ProjectionExtender.parseFarSetting("banana")));
        assertTrue(Float.isNaN(ProjectionExtender.parseFarSetting("10")), "too small: auto");
        assertEquals(0f, ProjectionExtender.parseFarSetting(" OFF "));
        assertEquals(5000f, ProjectionExtender.parseFarSetting("5000"));
        assertEquals(ProjectionExtender.MAX_FAR, ProjectionExtender.parseFarSetting("1e12"));
    }
}
