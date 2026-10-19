#version 450
// beacon_beam equivalent fragment stage: texture * colour * fog, full bright.

layout(set = 0, binding = 0) uniform sampler2D Sampler0;

layout(set = 1, binding = 0) readonly uniform FrameBlock {
    mat4 P;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    vec4 Light0;
    vec4 Light1;
} frame;

layout(location = 0) in vec2 vUV;
layout(location = 1) in vec4 vColor;
layout(location = 3) in float vDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, vUV) * vColor;
    float fogFactor = clamp((frame.FogEnd - vDistance) / (frame.FogEnd - frame.FogStart), 0.0, 1.0);
    color.rgb = mix(frame.FogColor.rgb, color.rgb, fogFactor);
    fragColor = color;
}
