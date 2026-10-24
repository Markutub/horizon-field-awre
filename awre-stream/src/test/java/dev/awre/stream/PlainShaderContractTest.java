package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the fix for a CI finding: vanilla core/position_color.vsh does NOT apply ModelOffset (shader dump of run 40060623947), so any
 * AWRE draw through it would place all tiles at the camera. The plain pipeline must use our own shader pair that applies it.
 * Does not run the shader (that is CI / in game); it only stops the two sides from drifting apart.
 */
class PlainShaderContractTest {
    private static String read(String... rel) throws Exception {
        for (String base : new String[]{"", "../"}) {
            Path p = Path.of(base + String.join("/", rel));
            if (Files.exists(p)) return Files.readString(p);
        }
        fail("file not found from " + Path.of("").toAbsolutePath() + ": " + String.join("/", rel));
        return null;
    }
    private static String norm(String s) { return s.replaceAll("//[^\\n]*", "").replaceAll("\\s+", " "); }
    private static final String CORE = "awre-fabric/src/main/resources/assets/minecraft/shaders/core/";

    @Test void plainVertexShaderAppliesModelOffsetAndTheStandardTransforms() throws Exception {
        String v = norm(read(CORE + "awre_terrain_plain.vsh"));
        assertTrue(v.contains("vec3 rel = Position + ModelOffset;"), "must apply ModelOffset itself");
        assertTrue(v.contains("gl_Position = ProjMat * ModelViewMat * vec4(rel, 1.0);"));
        assertTrue(v.contains("#moj_import <minecraft:dynamictransforms.glsl>") && v.contains("#moj_import <minecraft:projection.glsl>"));
    }

    @Test void plainFragmentShaderDiscardsTransparentAndModulatesLikeVanilla() throws Exception {
        String f = norm(read(CORE + "awre_terrain_plain.fsh"));
        assertTrue(f.contains("if (color.a == 0.0) { discard; }"));
        assertTrue(f.contains("fragColor = color * ColorModulator;"));
    }

    @Test void theRendererNeverDrawsThroughVanillaPositionColor() throws Exception {
        String src = read("awre-fabric/src/main/java/dev/awre/fabric/HorizonRenderer.java");
        assertFalse(src.contains("withVertexShader(\"core/position_color\")"), "vanilla position_color ignores ModelOffset");
        assertFalse(src.contains("withFragmentShader(\"core/position_color\")"));
        assertTrue(src.contains("withVertexShader(\"core/awre_terrain_plain\")") && src.contains("withVertexShader(\"core/awre_terrain\")"));
    }
}
