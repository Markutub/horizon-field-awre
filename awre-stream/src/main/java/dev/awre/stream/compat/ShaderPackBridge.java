package dev.awre.stream.compat;

/**
 * What AWRE needs to know about a shader-pack mod. Pure interface; the Fabric adapter provides the Iris implementation (awre-fabric
 * {@code IrisBridge}, built on {@link IrisReflection}). Step 1: detection (logs, bug reports, pipeline choice). Step 2: ask Iris to shade
 * AWRE terrain with the pack's terrain program ({@link #assignTerrainPipeline}); see docs/COMPAT_PLAN.md 2.3 for what is and is not verified.
 */
public interface ShaderPackBridge {
    /** True while a shader pack is rendering the world. Called on the client thread; must never throw. */
    boolean shaderPackActive();

    /** True while the shadow pass is being rendered (terrain should or should not be drawn into it). Default: never. */
    default boolean renderingShadowPass() { return false; }

    /**
     * Asks the shader-pack mod to render the given pipeline (a Mojang RenderPipeline, typed Object so this module stays free of
     * Minecraft classes) with the pack's TERRAIN program. @return true only if the mod accepted the assignment; never throws.
     */
    default boolean assignTerrainPipeline(Object renderPipeline) { return false; }

    ShaderPackBridge NONE = () -> false;
}
