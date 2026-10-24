#version 330

in vec4 vertexColor;
in float revealTau;
in float revealGrey;

out vec4 fragColor;

// Mirrors RevealAnimator.greyMix: mix(c, luma(c) * GREY_LUMA_WEIGHT + GREY_LIFT, grey).
const float GREY_LUMA_WEIGHT = 0.55;
const float GREY_LIFT = 0.30;

void main() {
    if (revealTau < 0.0) {
        discard;                                                // the wave has not reached this part of the tile yet
    }
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 target = vec3(luma * GREY_LUMA_WEIGHT + GREY_LIFT);
    fragColor = vec4(mix(color.rgb, target, revealGrey), color.a);
}
