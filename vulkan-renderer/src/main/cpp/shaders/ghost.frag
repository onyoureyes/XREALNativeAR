#version 450

layout(location = 0) in vec3 fragNormal;
layout(location = 1) in vec3 fragViewDir;

layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform GhostUBO {
    mat4 model;
    mat4 view;
    mat4 projection;
    vec4 ghostColor;
    float animPhase;
    float padding1;
    float padding2;
    float padding3;
} ubo;

void main() {
    vec3 N = normalize(fragNormal);
    vec3 V = normalize(fragViewDir);

    // 기본 디렉셔널 라이트 (상방)
    vec3 lightDir = normalize(vec3(0.3, -1.0, 0.5));
    float diffuse = max(dot(N, -lightDir), 0.0) * 0.6 + 0.4; // ambient 0.4

    // 프레넬 가장자리 글로우 (실루엣 강조)
    float fresnel = pow(1.0 - max(dot(N, V), 0.0), 3.0);

    // 시간 기반 펄스 (호흡 효과)
    float pulse = 0.85 + 0.15 * sin(ubo.animPhase * 6.28318);

    // 최종 컬러
    vec3 baseColor = ubo.ghostColor.rgb * diffuse;
    vec3 glowColor = vec3(1.0) * fresnel * 0.5; // 흰색 가장자리 글로우
    vec3 finalColor = (baseColor + glowColor) * pulse;

    float alpha = ubo.ghostColor.a * (0.5 + fresnel * 0.5) * pulse;

    outColor = vec4(finalColor, alpha);
}
