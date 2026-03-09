#version 450

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;

layout(location = 0) out vec3 fragNormal;
layout(location = 1) out vec3 fragViewDir;

layout(binding = 0) uniform GhostUBO {
    mat4 model;
    mat4 view;
    mat4 projection;
    vec4 ghostColor;    // RGBA
    float animPhase;    // 0.0 ~ 1.0 펄스 사이클
    float padding1;
    float padding2;
    float padding3;
} ubo;

void main() {
    vec4 worldPos = ubo.model * vec4(inPosition, 1.0);
    vec4 viewPos = ubo.view * worldPos;
    gl_Position = ubo.projection * viewPos;

    // 월드 노멀 (정규화는 프래그먼트에서)
    mat3 normalMatrix = mat3(ubo.model);
    fragNormal = normalMatrix * inNormal;

    // 뷰 방향 (카메라 위치는 view 역변환의 translation)
    fragViewDir = -viewPos.xyz;
}
