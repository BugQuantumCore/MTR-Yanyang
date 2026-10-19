#version 450
// rendertype_entity_cutout equivalent: two-sided directional light, 0.1 alpha
// cutoff, vanilla fog. Faithful to minecraft_mix_light, including its
// rgb-by-alpha multiplication, so the result matches the GL path exactly.

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
layout(location = 2) in vec3 vNormal;
layout(location = 3) in float vDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, vUV) * vColor;
    float lightAccum = min(1.0, (max(0.0, dot(vNormal, frame.Light0.xyz)) + max(0.0, dot(vNormal, frame.Light1.xyz))) * 0.6 + 0.4);
    color = vec4(color.rgb * lightAccum * color.a, color.a);
    if (color.a < 0.1) {
        discard;
    }
    float fogFactor = clamp((frame.FogEnd - vDistance) / (frame.FogEnd - frame.FogStart), 0.0, 1.0);
    color.rgb = mix(frame.FogColor.rgb, color.rgb, fogFactor);
    fragColor = color;
}
