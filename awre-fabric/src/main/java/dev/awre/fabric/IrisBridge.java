package dev.awre.fabric;

import dev.awre.stream.compat.IrisReflection;
import dev.awre.stream.compat.ShaderPackBridge;

/** Iris bridge for the Fabric adapter; the logic is the pure, tested {@link IrisReflection}. */
final class IrisBridge {
    private IrisBridge() { }

    /** @return a bridge, or {@link ShaderPackBridge#NONE} if Iris or its API is not there */
    static ShaderPackBridge create() { return IrisReflection.create(IrisBridge.class.getClassLoader()); }
}
