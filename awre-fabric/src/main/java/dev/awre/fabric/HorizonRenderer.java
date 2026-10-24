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

    /**
     * Reveal-effect pipeline: same vertex format, our own shader pair (assets/minecraft/shaders/core/awre_terrain.vsh/.fsh, in the
     * minecraft namespace so the already-compiling String overload applies). A holder class, so creating it can fail (reported, caught)
     * without ever affecting the plain pipeline above. API-ASSUMPTION: shader names resolve to those files and the vanilla includes
     * dynamictransforms.glsl / projection.glsl exist under those names.
     */
    private static final class RevealPipeline {
        static final RenderPipeline PIPELINE = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                        .withLocation("pipeline/awre_terrain_reveal")
                        .withVertexShader("core/awre_terrain")
                        .withFragmentShader("core/awre_terrain")
                        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
                        .withCull(false)
                        .build());
    }

    private static final dev.horizonfield.core.lod.LodModel TILE_MODEL = dev.horizonfield.core.lod.LodModel.defaults();

    private HorizonDrawPlanner<GpuMesh> planner;
    private long drawErrors;

    // reveal effect: -Dawre.reveal=off disables it; awre.reveal.<speed|maxDelay|duration|peak|depth|emerge|greyPeak|rippleAmp|rippleCycles> tune it
    private final dev.awre.stream.RevealAnimator animator = new dev.awre.stream.RevealAnimator(
            dev.awre.stream.RevealAnimator.Params.fromProperties(System::getProperty, m -> LOG.warn("[AWRE] reveal: {}", m)),
            !"off".equalsIgnoreCase(System.getProperty("awre.reveal", "on")));
    private final Matrix4f revealParams = new Matrix4f().set(animator.params().toUniform());
    private boolean revealBroken, revealLogged, revealThisFrame;

    void setPlanner(HorizonDrawPlanner<GpuMesh> p) {
        this.planner = p;
        if (p != null) p.setReveal(animator);                  // release() on dimension change / disconnect / shutdown resets the session
    }

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
        int submitted = 0;
        String stage = "writeTransform";        // the call in progress, so a failure says WHICH API assumption was wrong
        try {
            // CI-confirmed (run 22): writing a dynamic uniform is an encoder command and is refused while a render pass is open
            // ("Close the existing render pass before performing additional commands"). So every per-draw uniform is written FIRST;
            // the pass only binds the already-written slices. The lambdas capture the slice, so its type is never named here.
            // API-ASSUMPTION: DynamicUniforms.writeTransform(modelView, colour, modelOffset, texMatrix).
            List<java.util.function.Consumer<RenderPass>> uniformBinders = new java.util.ArrayList<>(draws.size());
            RenderPipeline pipeline = PIPELINE;
            boolean useReveal = animator.enabled() && !revealBroken;
            if (useReveal) {
                try { pipeline = RevealPipeline.PIPELINE; }
                catch (Throwable t) { revealBroken = true; useReveal = false; LOG.warn("[AWRE] reveal pipeline could not be created; using the plain terrain pipeline", t); }
            }
            long now = System.nanoTime();
            for (HorizonDrawPlanner.Draw<GpuMesh> d : draws) {
                Vector4f colour = new Vector4f(1, 1, 1, 1);
                Matrix4f texMat = new Matrix4f();
                if (useReveal) {
                    // the reveal shader reads its per-draw inputs from ColorModulator/TextureMat (see awre_terrain.vsh and RevealAnimator)
                    double size = TILE_MODEL.tileSize(d.key().lod());
                    var u = animator.forDraw(d.key(), d.offX() + camX, d.offZ() + camZ, size, camX, camZ, now);
                    colour.set(u.originRelX(), u.originRelZ(), u.revealAge(), u.tileAge());
                    texMat = revealParams;
                }
                var slice = RenderSystem.getDynamicUniforms().writeTransform(
                        viewMatrix, colour, new Vector3f(d.offX(), d.offY(), d.offZ()), texMat);
                uniformBinders.add(pass -> pass.setUniform("DynamicTransforms", slice));
            }
            if (useReveal && !revealLogged) { revealLogged = true; LOG.info("[AWRE] reveal effect active (params {})", animator.params()); }
            final RenderPipeline usedPipeline = pipeline;
            revealThisFrame = useReveal;
            stage = "getMainRenderTarget";
            // API-ASSUMPTION: main render target exposes color/depth texture views; CommandEncoder.createRenderPass(label, color, clear, depth, clearDepth).
            var target = Minecraft.getInstance().getMainRenderTarget();
            stage = "createRenderPass";
            RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "awre terrain", target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty());
            try (pass) {
                stage = "setPipeline";
                pass.setPipeline(usedPipeline);
                // API-ASSUMPTION: bindDefaultUniforms(pass) binds Projection/Fog/Globals.
                stage = "bindDefaultUniforms";
                RenderSystem.bindDefaultUniforms(pass);
                int i = 0;
                for (HorizonDrawPlanner.Draw<GpuMesh> d : draws) {
                    stage = "setUniform";
                    uniformBinders.get(i++).accept(pass);
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
            if (revealThisFrame) {                 // a shader/pipeline problem must never cost the terrain: this frame is lost, later frames use the plain pipeline
                revealBroken = true;
                LOG.warn("[AWRE] reveal pipeline draw failed at stage '" + stage + "'; falling back to the plain terrain pipeline from the next frame", e);
            }
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
