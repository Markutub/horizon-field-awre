package dev.awre.fabric;

import dev.awre.stream.compat.ShaderPackBridge;
import java.lang.reflect.Method;

/**
 * Iris detection through its public API, by reflection so awre-fabric needs no compile-time Iris dependency and loads without Iris.
 * API-ASSUMPTION (not verified against the running Iris): class {@code net.irisshaders.iris.api.v0.IrisApi} with static
 * {@code getInstance()} and instance {@code isShaderPackInUse()} / {@code isRenderingShadowPass()}. Any failure means "not active".
 */
final class IrisBridge implements ShaderPackBridge {
    private final Object api;
    private final Method inUse, shadow;

    private IrisBridge(Object api, Method inUse, Method shadow) { this.api = api; this.inUse = inUse; this.shadow = shadow; }

    /** @return a bridge, or {@link ShaderPackBridge#NONE} if Iris or its API is not there */
    static ShaderPackBridge create() {
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = c.getMethod("getInstance").invoke(null);
            Method inUse = c.getMethod("isShaderPackInUse");
            Method shadow = null;
            try { shadow = c.getMethod("isRenderingShadowPass"); } catch (NoSuchMethodException ignored) { }
            return new IrisBridge(api, inUse, shadow);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ShaderPackBridge.NONE;
        }
    }

    @Override public boolean shaderPackActive() { return call(inUse); }
    @Override public boolean renderingShadowPass() { return shadow != null && call(shadow); }

    private boolean call(Method m) {
        try { return Boolean.TRUE.equals(m.invoke(api)); } catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }
}
