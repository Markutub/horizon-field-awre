#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;

// AWRE terrain WITHOUT the reveal effect. Exists because vanilla core/position_color does NOT apply ModelOffset (confirmed from the
// CI shader dump: position_color.vsh = ProjMat * ModelViewMat * vec4(Position, 1.0)), so a draw through it would place every tile at the
// camera. Tile meshes are tile-local; ModelOffset = tile origin minus camera (written per draw by HorizonRenderer), applied here.
void main() {
    vec3 rel = Position + ModelOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(rel, 1.0);
    vertexColor = Color;
}
