package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pins the Java <-> GLSL contract that no unit test can execute: parameter packing order, the ColorModulator input layout and the grey
 * constants. It does NOT run the shader; that is verified by CI/in game. A failure here means one side was edited without the other.
 */
class RevealShaderContractTest {
    private static String read(String name) throws Exception {
        for (String base : new String[]{"awre-fabric", "../awre-fabric"}) {
            Path p = Path.of(base, "src/main/resources/assets/minecraft/shaders/core", name);
            if (Files.exists(p)) return Files.readString(p);
        }
        fail("shader file not found from " + Path.of("").toAbsolutePath() + ": " + name);
        return null;
    }

    /** Strips // comments, then collapses whitespace, so the checks do not depend on layout or commentary. */
    private static String norm(String s) { return s.replaceAll("//[^\\n]*", "").replaceAll("\\s+", " "); }

    @Test void vertexShaderReadsEveryParameterFromTheColumnRowTheJavaPackingWrites() throws Exception {
        String v = norm(read("awre_terrain.vsh"));
        // order = RevealAnimator.Params#toUniform: column 0 (speed, maxDelay, duration, peak), column 1 (depth, emerge, greyPeak, rippleAmp), column 2 (rippleCycles)
        String[][] map = {{"speed", "0][0"}, {"maxDelay", "0][1"}, {"duration", "0][2"}, {"peak", "0][3"},
                {"depth", "1][0"}, {"emerge", "1][1"}, {"greyPeak", "1][2"}, {"rippleAmp", "1][3"}, {"rippleCycles", "2][0"}};
        float[] packed = new RevealAnimator.Params(11, 12, 3, 14, 15, 0.5, 0.25, 3, 4).toUniform();
        float[] expectedByName = {11, 12, 3, 14, 15, 0.5f, 0.25f, 3, 4};
        int[] idx = {0, 1, 2, 3, 4, 5, 6, 7, 8};
        for (int i = 0; i < map.length; i++) {
            String decl = "float " + map[i][0] + " = TextureMat[" + map[i][1] + "];";
            assertTrue(v.contains(decl), "vertex shader must contain `" + decl + "`");
            int col = map[i][1].charAt(0) - '0', row = map[i][1].charAt(map[i][1].length() - 1) - '0';
            assertEquals(expectedByName[i], packed[col * 4 + row], 0f, map[i][0] + " is packed at column " + col + " row " + row + " (index " + (col * 4 + row) + ")");
            assertEquals(i, idx[i]);
        }
    }

    @Test void vertexShaderUsesTheClockAndOriginInputsAndTheSameFormulaShapes() throws Exception {
        String v = norm(read("awre_terrain.vsh"));
        assertTrue(v.contains("length(rel.xz - ColorModulator.xy)"), "wave origin = ColorModulator.xy (camera-relative)");
        assertTrue(v.contains("min(ColorModulator.w, ColorModulator.z - min(dist / speed, maxDelay))"), "tau = min(tileAge, revealAge - min(d/speed, maxDelay))");
        assertTrue(v.contains("vec3 rel = Position + ModelOffset;"), "the shader must apply ModelOffset itself");
        assertTrue(v.contains("lift = peak * env - depth * (1.0 - e) + rippleAmp * sin(2.0 * PI * rippleCycles * s) * r * r;"), "lift formula");
        assertTrue(v.contains("grey = greyPeak * env;"), "grey formula");
        assertTrue(v.contains("float s = tau / duration;"));
        assertTrue(v.contains("#moj_import <minecraft:dynamictransforms.glsl>") && v.contains("#moj_import <minecraft:projection.glsl>"));
    }

    @Test void fragmentShaderGreyConstantsMatchTheJavaConstantsAndHiddenFragmentsAreDiscarded() throws Exception {
        String f = norm(read("awre_terrain.fsh"));
        assertTrue(f.contains(String.format(Locale.ROOT, "const float GREY_LUMA_WEIGHT = %.2f;", RevealAnimator.GREY_LUMA_WEIGHT)), "luma weight drifted");
        assertTrue(f.contains(String.format(Locale.ROOT, "const float GREY_LIFT = %.2f;", RevealAnimator.GREY_LIFT)), "grey lift drifted");
        assertTrue(f.contains("if (revealTau < 0.0) { discard; }"), "not-yet-revealed fragments are discarded");
        assertTrue(f.contains("mix(color.rgb, target, revealGrey)"));
        assertTrue(f.contains("dot(color.rgb, vec3(0.2126, 0.7152, 0.0722))"), "same luma weights as RevealAnimator#greyMix");
    }
}
