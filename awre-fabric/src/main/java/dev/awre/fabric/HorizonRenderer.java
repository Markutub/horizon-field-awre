package dev.awre.fabric;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.awre.protocol.TileKey;
import dev.awre.stream.HorizonDrawPlanner;
import dev.awre.stream.MeshResidency;
import dev.awre.stream.ProjectionExtender;
import dev.awre.stream.VertexPacker;
import dev.horizonfield.core.terrain.TerrainMesher;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
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

    /**
     * Plain terrain pipeline: our own shader pair (assets/minecraft/shaders/core/awre_terrain_plain.vsh/.fsh) that applies ModelOffset.
     * NOT vanilla core/position_color: the CI shader dump (run 40060623947) shows that shader ignores ModelOffset, so it would draw every
     * tile at the camera. A holder class (like RevealPipeline) so a failure to create it is reported and caught, never fatal to the mod.
     * Same snippet, vertex format and shader-include names as the reveal pipeline, which compiles and draws in CI.
     */
    private static final class PlainPipeline {
        static final RenderPipeline PIPELINE = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                        .withLocation("pipeline/awre_terrain_plain")
                        .withVertexShader("core/awre_terrain_plain")
                        .withFragmentShader("core/awre_terrain_plain")
                        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
                        .withCull(false)
                        .build());
    }

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

    // reveal effect settings: config/awre-reveal.txt, overridden by JVM properties (-Dawre.reveal=off, -Dawre.reveal.<key>=...); see RevealSettings.
    // Created on first use, NOT at construction: this renderer is built before the mod has read its config files.
    private dev.awre.stream.RevealAnimator animator;
    private Matrix4f revealParams;

    private void ensureReveal() {
        if (animator != null) return;
        var get = dev.awre.stream.RevealSettings.provider(System::getProperty);
        animator = new dev.awre.stream.RevealAnimator(
                dev.awre.stream.RevealAnimator.Params.fromProperties(get, m -> LOG.warn("[AWRE] reveal: {}", m)),
                dev.awre.stream.RevealSettings.enabled(get));
        revealParams = new Matrix4f().set(animator.params().toUniform());
    }

    private boolean revealBroken, revealLogged, revealThisFrame;

    void setPlanner(HorizonDrawPlanner<GpuMesh> p) {
        ensureReveal();
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

    // ---- far plane (docs/COMPAT_PLAN.md 1.1). Signatures are from the CI api dump of the real 1.21.11 jar, not memory:
    // GameRenderer#getDepthFar(), GameRenderer#getProjectionMatrix(float), GameRenderer.PROJECTION_Z_NEAR, PerspectiveProjectionMatrixBuffer#getBuffer(Matrix4f),
    // RenderSystem#setProjectionMatrix(GpuBufferSlice, ProjectionType) / #getProjectionMatrixBuffer() / #getProjectionType(), Minecraft.options.fov().
    // STILL UNVERIFIED: that getProjectionMatrix takes degrees (vanilla passes options.fov, e.g. 70, to it) and that getBuffer's write is an
    // encoder command (so it is done before the render pass opens, like the uniforms).
    private float farSetting = Float.NaN;          // NaN = auto, 0 = off (vanilla projection), >0 = explicit far plane in blocks
    private double viewMaxDistance = 16384;
    private PerspectiveProjectionMatrixBuffer projectionBuffer;
    private boolean farLogged, farRefusedLogged;

    /** @param setting value of -Dawre.far ("auto" | "off" | blocks); @param viewMaxDistance the planner's max distance in blocks */
    void configureFar(String setting, double viewMaxDistance) {
        this.farSetting = ProjectionExtender.parseFarSetting(setting);
        this.viewMaxDistance = viewMaxDistance;
    }

    // ---- shader packs (docs/COMPAT_PLAN.md 2.3) ----
    private dev.awre.stream.compat.ShaderPackBridge shaderBridge = dev.awre.stream.compat.ShaderPackBridge.NONE;
    private boolean irisAssigned;                  // true only after Iris ACCEPTED the plain pipeline (experimental, -Dawre.iris=assign)
    private boolean plainBroken, plainLogged;

    /**
     * @param assign EXPERIMENTAL (-Dawre.iris=assign): ask the shader-pack mod (Iris) to render the PLAIN terrain pipeline with the pack's
     *               TERRAIN program. Default false: detect and log only, AWRE keeps drawing with its own shaders, which place tiles
     *               correctly but are not shaded by the pack. Unverified against a real Iris for 1.21.11: whether Iris has the method,
     *               and whether the pack's terrain program places tiles correctly (our pipeline has no chunk-offset uniforms).
     */
    void setShaderBridge(dev.awre.stream.compat.ShaderPackBridge bridge, boolean assign) {
        this.shaderBridge = bridge == null ? dev.awre.stream.compat.ShaderPackBridge.NONE : bridge;
        if (this.shaderBridge == dev.awre.stream.compat.ShaderPackBridge.NONE) return;
        if (!assign) { LOG.info("[AWRE] Iris detected: detect-only (AWRE terrain keeps its own shaders and is not shaded by packs); -Dawre.iris=assign is experimental"); return; }
        boolean ok;
        try { ok = this.shaderBridge.assignTerrainPipeline(PlainPipeline.PIPELINE); } catch (Throwable t) { ok = false; }
        irisAssigned = ok;
        LOG.info("[AWRE] Iris assignPipeline(plain terrain pipeline -> TERRAIN): {}", ok ? "ACCEPTED (experimental: placement under a pack is unverified)" : "NOT available");
    }

    /** The plain pipeline, or null (logged once) if it cannot be created. */
    private RenderPipeline plainPipelineOrNull() {
        if (plainBroken) return null;
        try {
            RenderPipeline p = PlainPipeline.PIPELINE;
            if (!plainLogged) { plainLogged = true; LOG.info("[AWRE] plain terrain pipeline in use (reveal effect off or unavailable)"); }
            return p;
        } catch (Throwable t) {
            plainBroken = true;
            LOG.error("[AWRE] plain terrain pipeline could not be created; AWRE terrain is NOT drawn (vanilla position_color would misplace tiles)", t);
            return null;
        }
    }

    /** Client stopping (render thread): closes the projection buffer. */
    void shutdown() {
        if (projectionBuffer != null) { projectionBuffer.close(); projectionBuffer = null; }
    }

    /**
     * The level projection with a larger far plane, as a GPU slice, or null to keep vanilla's. Writes a GPU buffer, so it must be called
     * BEFORE the render pass opens. FOV note: only the base FOV option is used; FOV effects (sprinting, potions) are not tracked here, so
     * while they are active AWRE terrain is drawn with a slightly different field of view than vanilla.
     */
    private GpuBufferSlice extendedProjection(Minecraft mc) {
        if (farSetting == 0f) return null;
        GameRenderer gr = mc.gameRenderer;
        float vanillaFar = gr.getDepthFar();
        float wanted = Float.isNaN(farSetting) ? ProjectionExtender.chooseFar(viewMaxDistance, vanillaFar) : Math.max(vanillaFar, farSetting);
        if (!(wanted > vanillaFar)) return null;
        Matrix4f vanilla = gr.getProjectionMatrix(mc.options.fov().get().floatValue());
        ProjectionExtender.Z z = ProjectionExtender.extendFar(vanilla.m22(), vanilla.m32(), vanilla.m23(), GameRenderer.PROJECTION_Z_NEAR, wanted);
        if (z == null) {
            if (!farRefusedLogged) {
                farRefusedLogged = true;
                LOG.warn("[AWRE] far plane NOT extended: the level projection is not a standard perspective matrix (m22={} m32={} m23={}, expected near {}); terrain will be clipped at vanilla's far plane {}",
                        vanilla.m22(), vanilla.m32(), vanilla.m23(), GameRenderer.PROJECTION_Z_NEAR, vanillaFar);
            }
            return null;
        }
        if (!farLogged) {
            farLogged = true;
            LOG.info("[AWRE] far plane: vanilla={} awre={} (near={}, implied near of vanilla matrix={}, setting={})", vanillaFar, Math.min(wanted, ProjectionExtender.MAX_FAR),
                    GameRenderer.PROJECTION_Z_NEAR, ProjectionExtender.impliedNear(vanilla.m22(), vanilla.m32()), Float.isNaN(farSetting) ? "auto" : farSetting);
        }
        if (projectionBuffer == null) projectionBuffer = new PerspectiveProjectionMatrixBuffer("awre horizon projection");
        return projectionBuffer.getBuffer(new Matrix4f(vanilla).m22(z.m22()).m32(z.m32()));
    }

    // ---- per frame ----

    /**
     * @param viewMatrix camera rotation (world -> view, WITHOUT translation); the translation is the per-draw offset
     * @param camX/Y/Z   camera position in world coordinates
     */
    void render(Matrix4f viewMatrix, double camX, double camY, double camZ) {
        ensureReveal();
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
            RenderPipeline pipeline = null;
            // only when Iris accepted the plain pipeline AND a pack is active does the plain pipeline replace the reveal one (the reveal
            // shader's own uniforms cannot run inside a pack's program)
            boolean useReveal = animator.enabled() && !revealBroken && !(irisAssigned && shaderBridge.shaderPackActive());
            if (useReveal) {
                try { pipeline = RevealPipeline.PIPELINE; }
                catch (Throwable t) { revealBroken = true; useReveal = false; LOG.warn("[AWRE] reveal pipeline could not be created; using the plain terrain pipeline", t); }
            }
            if (pipeline == null) {
                pipeline = plainPipelineOrNull();
                if (pipeline == null) { p.recordSubmitted(0); return; }     // drawing through a pipeline that misplaces tiles is worse than not drawing
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
            stage = "projection";
            GpuBufferSlice extended = extendedProjection(Minecraft.getInstance());      // writes a GPU buffer: before the pass opens
            stage = "getMainRenderTarget";
            // API-ASSUMPTION: main render target exposes color/depth texture views; CommandEncoder.createRenderPass(label, color, clear, depth, clearDepth).
            var target = Minecraft.getInstance().getMainRenderTarget();
            GpuBufferSlice previousProjection = null;
            ProjectionType previousType = null;
            if (extended != null) {                  // the rest of vanilla's level rendering must see ITS projection again afterwards
                previousProjection = RenderSystem.getProjectionMatrixBuffer();
                previousType = RenderSystem.getProjectionType();
                RenderSystem.setProjectionMatrix(extended, ProjectionType.PERSPECTIVE);
            }
            try {
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
            } finally {
                if (extended != null && previousProjection != null && previousType != null) RenderSystem.setProjectionMatrix(previousProjection, previousType);
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
