#version 450
// beacon_beam equivalent vertex stage: full-bright, no directional light, no
// normal matrix — the LIGHT / ALWAYS_ON_LIGHT render stages never shade.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV;
layout(location = 2) in vec3 Normal;

layout(push_constant) uniform PushBlock {
    vec4 Color;
    mat4 MV;
    mat3 N;
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
layout(location = 3) out float vDistance;

void main() {
    vec4 mvPosition = push.MV * vec4(Position, 1.0);
    gl_Position = frame.P * mvPosition;
    vUV = UV;
    vColor = push.Color;
    vDistance = length(mvPosition.xyz);
}
