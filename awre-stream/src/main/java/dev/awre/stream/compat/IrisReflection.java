package dev.awre.stream.compat;

import java.lang.reflect.Method;

/**
 * Iris through its public API v0, by reflection, so AWRE needs no compile-time Iris dependency and loads without Iris. Pure Java: the
 * logic is tested against fake classes with Iris's package and names.
 *
 * <p>Sources for the API shape (secondary, a generated summary of Iris's source at one commit, NOT checked against an Iris build for
 * Minecraft 1.21.11): {@code IrisApi.getInstance()}, {@code isShaderPackInUse()}, {@code isRenderingShadowPass()}, and (API v0.3)
 * {@code assignPipeline(RenderPipeline, IrisProgram)} with an {@code IrisProgram} enum that has a TERRAIN constant. Everything is looked
 * up by name and parameter type and every failure means "not available": a missing method is reported by {@link #hasAssignPipeline()},
 * never an exception.
 */
public final class IrisReflection implements ShaderPackBridge {
    static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";

    private final Object api;
    private final Method inUse, shadow, assign;
    private final Object terrainConstant;

    private IrisReflection(Object api, Method inUse, Method shadow, Method assign, Object terrainConstant) {
        this.api = api; this.inUse = inUse; this.shadow = shadow; this.assign = assign; this.terrainConstant = terrainConstant;
    }

    /** @return a bridge, or {@link ShaderPackBridge#NONE} if Iris or its API is not there */
    public static ShaderPackBridge create(ClassLoader loader) {
        try {
            Class<?> iface = Class.forName(API_CLASS, false, loader);
            Object api = iface.getMethod("getInstance").invoke(null);
            Method inUse = iface.getMethod("isShaderPackInUse");
            Method shadow = optional(iface, "isRenderingShadowPass");
            Method assign = null;
            Object terrain = null;
            for (Method m : iface.getMethods()) {
                if (m.getName().equals("assignPipeline") && m.getParameterCount() == 2 && m.getParameterTypes()[1].isEnum()) {
                    for (Object c : m.getParameterTypes()[1].getEnumConstants()) {
                        if (((Enum<?>) c).name().equals("TERRAIN")) { assign = m; terrain = c; }
                    }
                }
            }
            return new IrisReflection(api, inUse, shadow, assign, terrain);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ShaderPackBridge.NONE;
        }
    }

    private static Method optional(Class<?> c, String name) {
        try { return c.getMethod(name); } catch (NoSuchMethodException e) { return null; }
    }

    /** Whether this Iris exposes assignPipeline(pipeline, IrisProgram) with a TERRAIN program. */
    public boolean hasAssignPipeline() { return assign != null; }

    @Override public boolean shaderPackActive() { return call(inUse); }
    @Override public boolean renderingShadowPass() { return shadow != null && call(shadow); }

    @Override public boolean assignTerrainPipeline(Object renderPipeline) {
        if (assign == null || renderPipeline == null || !assign.getParameterTypes()[0].isInstance(renderPipeline)) return false;
        try { assign.invoke(api, renderPipeline, terrainConstant); return true; } catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }

    private boolean call(Method m) {
        try { return Boolean.TRUE.equals(m.invoke(api)); } catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }
}
