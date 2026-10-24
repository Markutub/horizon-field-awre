package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.awre.stream.compat.IrisReflection;
import dev.awre.stream.compat.ShaderPackBridge;
import net.irisshaders.iris.api.v0.FakeIris;
import net.irisshaders.iris.api.v0.FakePipeline;
import org.junit.jupiter.api.Test;

/** IrisReflection against fakes that have Iris's package and method names. Proves the lookup logic, NOT that a real Iris matches. */
class IrisReflectionTest {
    private static ShaderPackBridge bridge() { return IrisReflection.create(IrisReflectionTest.class.getClassLoader()); }

    @Test void withoutIrisOnTheClasspathTheBridgeIsNone() {
        ClassLoader empty = new ClassLoader(null) { };
        assertTrue(IrisReflection.create(empty) == ShaderPackBridge.NONE);
        assertTrue(!ShaderPackBridge.NONE.shaderPackActive() && !ShaderPackBridge.NONE.assignTerrainPipeline(new Object()));
    }

    @Test void packAndShadowStateAreReadLive() {
        ShaderPackBridge b = bridge();
        FakeIris f = FakeIris.INSTANCE;
        f.packInUse = false; f.shadowPass = false;
        assertTrue(!b.shaderPackActive() && !b.renderingShadowPass());
        f.packInUse = true; f.shadowPass = true;
        assertTrue(b.shaderPackActive() && b.renderingShadowPass());
        f.packInUse = false; f.shadowPass = false;
    }

    @Test void assignTerrainPipelineCallsAssignPipelineWithTheTerrainProgram() {
        ShaderPackBridge b = bridge();
        assertTrue(b instanceof IrisReflection ir && ir.hasAssignPipeline());
        FakeIris f = FakeIris.INSTANCE; f.assigned.clear(); f.assignThrows = false;
        assertTrue(b.assignTerrainPipeline(new FakePipeline()));
        assertEquals(java.util.List.of("TERRAIN"), f.assigned);
    }

    @Test void wrongTypeNullAndThrowingIrisAreRefusedWithoutAnException() {
        ShaderPackBridge b = bridge();
        FakeIris f = FakeIris.INSTANCE; f.assigned.clear();
        assertTrue(!b.assignTerrainPipeline("not a pipeline"));
        assertTrue(!b.assignTerrainPipeline(null));
        assertEquals(0, f.assigned.size());
        f.assignThrows = true;
        try { assertTrue(!b.assignTerrainPipeline(new FakePipeline())); } finally { f.assignThrows = false; }
    }
}
