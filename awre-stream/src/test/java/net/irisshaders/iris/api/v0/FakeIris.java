package net.irisshaders.iris.api.v0;

import java.util.ArrayList;
import java.util.List;

/** TEST FAKE implementation with switchable state; records assignments. */
public final class FakeIris implements IrisApi {
    public static final FakeIris INSTANCE = new FakeIris();
    public volatile boolean packInUse, shadowPass, assignThrows;
    public final List<String> assigned = new ArrayList<>();
    @Override public boolean isShaderPackInUse() { return packInUse; }
    @Override public boolean isRenderingShadowPass() { return shadowPass; }
    @Override public void assignPipeline(FakePipeline p, IrisProgram program) {
        if (assignThrows) throw new IllegalStateException("boom");
        assigned.add(program.name());
    }
}
