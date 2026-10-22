package dev.awre.stream.compat;

/**
 * What AWRE needs to know about a shader-pack mod. Pure interface; the Fabric adapter provides an Iris implementation
 * (awre-fabric IrisBridge). STEP 1 (this file): detection only, so logs and bug reports say whether a pack was active.
 * STEP 2 (not started, see docs/COMPAT_PLAN.md): drawing AWRE terrain inside the pack's pipeline so it is shaded and casts shadows.
 */
public interface ShaderPackBridge {
    /** True while a shader pack is rendering the world. Called on the client thread; must never throw. */
    boolean shaderPackActive();

    /** True while the shadow pass is being rendered (terrain should or should not be drawn into it). Default: never. */
    default boolean renderingShadowPass() { return false; }

    ShaderPackBridge NONE = () -> false;
}
