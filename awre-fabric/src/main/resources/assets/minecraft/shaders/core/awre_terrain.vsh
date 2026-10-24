#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
out float revealTau;
out float revealGrey;

// AWRE terrain reveal (the formulas mirror dev.awre.stream.RevealAnimator; RevealShaderContractTest pins the packing below).
//
// Per-draw uniforms are smuggled through the standard DynamicTransforms block, which this shader does not otherwise need:
//   ColorModulator = (wave origin rel. to camera x, wave origin rel. to camera z, revealAge [s], tileAge [s])
//   TextureMat     = effect parameters, column-major (see RevealAnimator.Params#toUniform)
//   ModelOffset    = tile origin minus camera (the vanilla position_color shader does not apply it, so it is applied here)
// revealAge/tileAge >= 10000 means "finished": offset 0, no grey, nothing hidden.

const float PI = 3.14159265358979;

void main() {
    vec3 rel = Position + ModelOffset;                          // camera-relative position (float-safe at any world coordinate)

    float speed = TextureMat[0][0];
    float maxDelay = TextureMat[0][1];
    float duration = TextureMat[0][2];
    float peak = TextureMat[0][3];
    float depth = TextureMat[1][0];
    float emerge = TextureMat[1][1];
    float greyPeak = TextureMat[1][2];
    float rippleAmp = TextureMat[1][3];
    float rippleCycles = TextureMat[2][0];

    float dist = length(rel.xz - ColorModulator.xy);
    float tau = min(ColorModulator.w, ColorModulator.z - min(dist / speed, maxDelay));
    float s = tau / duration;

    float lift = 0.0;
    float grey = 0.0;
    if (s >= 0.0 && s < 1.0) {
        float env = sin(PI * s);
        env = env * env;
        float e = emerge > 0.0 ? clamp(s / emerge, 0.0, 1.0) : 1.0;
        e = e * e * (3.0 - 2.0 * e);
        float r = 1.0 - s;
        lift = peak * env - depth * (1.0 - e) + rippleAmp * sin(2.0 * PI * rippleCycles * s) * r * r;
        grey = greyPeak * env;
    }
    rel.y += lift;

    gl_Position = ProjMat * ModelViewMat * vec4(rel, 1.0);
    vertexColor = Color;
    revealTau = tau;
    revealGrey = grey;
}
