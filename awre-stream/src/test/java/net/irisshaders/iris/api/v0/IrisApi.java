package net.irisshaders.iris.api.v0;

/** TEST FAKE with Iris's package and names (shape per Iris's published API v0): lets IrisReflection be tested without Iris. */
public interface IrisApi {
    static IrisApi getInstance() { return FakeIris.INSTANCE; }
    boolean isShaderPackInUse();
    boolean isRenderingShadowPass();
    void assignPipeline(FakePipeline pipeline, IrisProgram program);
}
