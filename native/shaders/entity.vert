#version 450
// Shared vertex stage for the entity shader families (cutout + translucent).
// Reproduces vanilla's rendertype_entity_* vertex transform with the fork's
// scalar lighting folded into the per-item colour: the model-view matrix and
// the inverse-transpose normal matrix both arrive as push constants.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV;
layout(location = 2) in vec3 Normal;

layout(push_constant) uniform PushBlock {
    vec4 Color; // Kd * (v, v, v, 1)
    mat4 MV;    // column-major model-view
    mat3 N;     // inverse-transpose model-view 3x3 (view space)
} push;

layout(set = 1, binding = 0) readonly uniform FrameBlock {
    mat4 P;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    vec4 Light0;
    vec4 Light1;
} frame;

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec3 vNormal;
layout(location = 3) out float vDistance;

void main() {
    vec4 mvPosition = push.MV * vec4(Position, 1.0);
    gl_Position = frame.P * mvPosition;
    vUV = UV;
    vColor = push.Color;
    vNormal = normalize(push.N * normalize(Normal));
    vDistance = length(mvPosition.xyz);
}
