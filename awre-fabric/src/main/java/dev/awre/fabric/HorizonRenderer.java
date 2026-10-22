package dev.awre.fabric;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.awre.protocol.TileKey;
import dev.awre.stream.HorizonDrawPlanner;
import dev.awre.stream.MeshResidency;
import dev.awre.stream.VertexPacker;
import dev.horizonfield.core.terrain.TerrainMesher;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ONLY class that touches Minecraft's GPU API. Everything it decides (what to upload, what to draw, camera offsets, limits,
 * counters) comes from the pure-Java, unit-tested {@link HorizonDrawPlanner}.
 *
 * <p>STATUS: written against the Minecraft 1.21.11 (Mojang names) blaze3d API from memory of its shape; NOT compiled, NOT run.
 * Every assumption is marked {@code API-ASSUMPTION}; the first Gradle/CI build decides which are wrong. docs/phases/PHASE_01.md lists them.
 *
 * <p>Design: one vertex buffer + one index buffer per tile (tile-local float positions, see TerrainMesher#meshLocal), drawn with a
 * per-draw model offset (tile origin - camera, computed in double by the planner), so the camera moving never re-uploads anything.
 * Culling is OFF for the baseline (winding errors would otherwise make terrain invisible); depth test on. All calls on the render thread.
 */
final class HorizonRenderer implements MeshResidency.Backend<HorizonRenderer.GpuMesh> {
    static final Logger LOG = LoggerFactory.getLogger("AWRE");

    /** GPU objects of one tile. */
    record GpuMesh(GpuBuffer vertices, GpuBuffer indices) { }

    // API-ASSUMPTION: RenderPipeline.builder(snippet) + RenderPipelines.register exist; "core/position_color" is a vanilla shader pair;
    // MATRICES_PROJECTION_SNIPPET declares the DynamicTransforms + Projection uniforms position_color needs.
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/awre_terrain")
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
                    .withCull(false)
                    .build());

    private HorizonDrawPlanner<GpuMesh> planner;
    private long drawErrors;

    void setPlanner(HorizonDrawPlanner<GpuMesh> p) { this.planner = p; }

    // ---- MeshResidency.Backend: called by the planner on the render thread ----

    @Override public GpuMesh upload(TileKey key, TerrainMesher.Mesh mesh) {
        // API-ASSUMPTION: GpuDevice.createBuffer(Supplier<String> label, int usage, ByteBuffer data) and the USAGE_* constants.
        var device = RenderSystem.getDevice();
        GpuBuffer v = null, i = null;
        try {
            v = device.createBuffer(() -> "awre vertices " + key.tileX() + "," + key.tileZ() + " L" + key.lod(), GpuBuffer.USAGE_VERTEX, VertexPacker.packVertices(mesh));
            i = device.createBuffer(() -> "awre indices " + key.tileX() + "," + key.tileZ() + " L" + key.lod(), GpuBuffer.USAGE_INDEX, VertexPacker.packIndices(mesh));
            return new GpuMesh(v, i);
        } catch (RuntimeException e) {
            if (v != null) v.close();                  // never leave half a tile behind
            if (i != null) i.close();
            throw e;
        }
    }

    @Override public void free(GpuMesh m) {
        m.vertices().close();
        m.indices().close();
    }

    // ---- per frame ----

    /**
     * @param viewMatrix camera rotation (world -> view, WITHOUT translation); the translation is the per-draw offset
     * @param camX/Y/Z   camera position in world coordinates
     */
    void render(Matrix4f viewMatrix, double camX, double camY, double camZ) {
        HorizonDrawPlanner<GpuMesh> p = planner;
        if (p == null) return;
        List<HorizonDrawPlanner.Draw<GpuMesh>> draws = p.frame(camX, camY, camZ);   // bounded uploads happen in here
        if (draws.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        int submitted = 0;
        String stage = "getMainRenderTarget";   // the call in progress, so a failure says WHICH API assumption was wrong
        // API-ASSUMPTION: main render target exposes color/depth texture views; CommandEncoder.createRenderPass(label, color, clear, depth, clearDepth).
        var target = mc.getMainRenderTarget();
        try {
            stage = "createRenderPass";
            RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "awre terrain", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty());
            try (pass) {
            stage = "setPipeline";
            pass.setPipeline(PIPELINE);
            // API-ASSUMPTION: bindDefaultUniforms(pass) binds Projection/Fog/Globals; DynamicUniforms.writeTransform(modelView, colour, modelOffset, texMatrix).
            stage = "bindDefaultUniforms";
            RenderSystem.bindDefaultUniforms(pass);
            for (HorizonDrawPlanner.Draw<GpuMesh> d : draws) {
                stage = "writeTransform";
                var slice = RenderSystem.getDynamicUniforms().writeTransform(
                        viewMatrix, new Vector4f(1, 1, 1, 1), new Vector3f(d.offX(), d.offY(), d.offZ()), new Matrix4f());
                stage = "setUniform";
                pass.setUniform("DynamicTransforms", slice);
                stage = "setVertexBuffer";
                pass.setVertexBuffer(0, d.handle().vertices());
                stage = "setIndexBuffer";
                pass.setIndexBuffer(d.handle().indices(), VertexFormat.IndexType.INT);
                stage = "drawIndexed";
                pass.drawIndexed(0, 0, d.indexCount(), 1);      // API-ASSUMPTION: (baseVertex, firstIndex, indexCount, instanceCount)
                submitted++;
            }
            stage = "closePass";
            }
        } catch (RuntimeException e) {
            // full stack trace for the first failures: the CI log must show the real cause, not just the exception class
            if (drawErrors++ < 3) LOG.warn("[AWRE] draw failed at stage '" + stage + "' after " + submitted + " of " + draws.size() + " draws", e);
            else if (drawErrors < 6) LOG.warn("[AWRE] draw failed at stage '{}' after {} of {} draws: {}", stage, submitted, draws.size(), e.toString());
            submitted = 0;                                        // do not count a failed pass as submitted
        }
        p.recordSubmitted(submitted);
    }

    /** Frees every GPU mesh now (render thread) or as soon as the render thread runs the task. */
    void releaseAll() {
        HorizonDrawPlanner<GpuMesh> p = planner;
        if (p == null) return;
        if (RenderSystem.isOnRenderThread()) p.release();
        else Minecraft.getInstance().execute(p::release);
    }
}
