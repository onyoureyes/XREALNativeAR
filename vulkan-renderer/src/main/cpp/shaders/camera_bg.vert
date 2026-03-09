#version 450

// Fullscreen triangle — 3개 버텍스로 전체 화면 커버
// gl_VertexIndex 0,1,2 → NDC 좌표 + UV 자동 생성

layout(location = 0) out vec2 fragTexCoord;

void main() {
    // Fullscreen triangle trick: single triangle covers entire screen
    // Index 0: (-1, -1), Index 1: (3, -1), Index 2: (-1, 3)
    vec2 pos = vec2(
        float((gl_VertexIndex << 1) & 2) * 2.0 - 1.0,
        float(gl_VertexIndex & 2) * 2.0 - 1.0
    );

    // UV: (0,0) top-left to (1,1) bottom-right
    fragTexCoord = pos * 0.5 + 0.5;

    gl_Position = vec4(pos, 0.0, 1.0);
}
