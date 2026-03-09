#pragma once

/**
 * math_utils.h -- 3D 수학 유틸리티 (헤더 온리).
 *
 * 좌표계 규약 (PoseTransform.kt 참조):
 * - OpenVINS 포즈: camera-to-world (Hamilton 쿼터니언, w=스칼라)
 * - 카메라 프레임: X=오른쪽, Y=아래, Z=앞 (OpenCV 표준 = Vulkan NDC)
 * - 행렬: column-major float[16] (GLSL std140 호환)
 *
 * Vulkan NDC: X=right, Y=down, Z=[0,1] depth
 */

#include <cmath>
#include <cstring>

namespace math {

// ── 4×4 행렬 (column-major) ──

struct Mat4 {
    float m[16]; // column-major: m[col*4 + row]

    static Mat4 identity() {
        Mat4 r;
        memset(r.m, 0, sizeof(r.m));
        r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.0f;
        return r;
    }

    // Column-major accessors: element(row, col)
    float& at(int row, int col) { return m[col * 4 + row]; }
    float at(int row, int col) const { return m[col * 4 + row]; }
};

inline Mat4 multiply(const Mat4& a, const Mat4& b) {
    Mat4 r;
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float sum = 0.0f;
            for (int k = 0; k < 4; k++) {
                sum += a.at(row, k) * b.at(k, col);
            }
            r.at(row, col) = sum;
        }
    }
    return r;
}

// ── 쿼터니언 → 회전 행렬 ──

/**
 * Hamilton 쿼터니언 → camera-to-world 회전 행렬 (column-major).
 * OpenVINS 출력: (qx, qy, qz, qw) where qw=scalar.
 */
inline Mat4 quaternionToRotation(float qx, float qy, float qz, float qw) {
    float xx = qx * qx, yy = qy * qy, zz = qz * qz;
    float xy = qx * qy, xz = qx * qz, yz = qy * qz;
    float wx = qw * qx, wy = qw * qy, wz = qw * qz;

    Mat4 r = Mat4::identity();
    // Row 0
    r.at(0, 0) = 1.0f - 2.0f * (yy + zz);
    r.at(0, 1) = 2.0f * (xy - wz);
    r.at(0, 2) = 2.0f * (xz + wy);
    // Row 1
    r.at(1, 0) = 2.0f * (xy + wz);
    r.at(1, 1) = 1.0f - 2.0f * (xx + zz);
    r.at(1, 2) = 2.0f * (yz - wx);
    // Row 2
    r.at(2, 0) = 2.0f * (xz - wy);
    r.at(2, 1) = 2.0f * (yz + wx);
    r.at(2, 2) = 1.0f - 2.0f * (xx + yy);

    return r;
}

/**
 * Pose(x,y,z,qx,qy,qz,qw) → camera-to-world 4×4 행렬.
 */
inline Mat4 poseToMatrix(float x, float y, float z,
                          float qx, float qy, float qz, float qw) {
    Mat4 r = quaternionToRotation(qx, qy, qz, qw);
    r.at(0, 3) = x;
    r.at(1, 3) = y;
    r.at(2, 3) = z;
    return r;
}

/**
 * Rigid transform 역변환 (view matrix = world-to-camera).
 * 입력: camera-to-world [R|t]
 * 출력: world-to-camera [R^T | -R^T·t]
 */
inline Mat4 invertRigid(const Mat4& cam2world) {
    Mat4 r = Mat4::identity();

    // 회전 전치
    for (int i = 0; i < 3; i++) {
        for (int j = 0; j < 3; j++) {
            r.at(i, j) = cam2world.at(j, i);
        }
    }

    // -R^T × t
    float tx = cam2world.at(0, 3);
    float ty = cam2world.at(1, 3);
    float tz = cam2world.at(2, 3);

    r.at(0, 3) = -(r.at(0, 0) * tx + r.at(0, 1) * ty + r.at(0, 2) * tz);
    r.at(1, 3) = -(r.at(1, 0) * tx + r.at(1, 1) * ty + r.at(1, 2) * tz);
    r.at(2, 3) = -(r.at(2, 0) * tx + r.at(2, 1) * ty + r.at(2, 2) * tz);

    return r;
}

// ── 투영 행렬 ──

/**
 * OpenCV 카메라 내재 파라미터 → Vulkan 투영 행렬.
 *
 * Vulkan NDC: X∈[-1,1], Y∈[-1,1], Z∈[0,1] (depth)
 * OpenCV 카메라: X=right, Y=down, Z=forward → Vulkan과 동일 방향.
 *
 * @param fx,fy 초점거리 (pixels)
 * @param cx,cy 주점 (pixels)
 * @param width,height 이미지 크기
 * @param near,far 클리핑 평면
 */
inline Mat4 intrinsicsToProjection(float fx, float fy, float cx, float cy,
                                     float width, float height,
                                     float near = 0.05f, float far = 100.0f) {
    Mat4 p;
    memset(p.m, 0, sizeof(p.m));

    // Vulkan projection from pinhole camera model
    // NDC x = (2*fx/w) * (X/Z) + (2*cx/w - 1)
    // NDC y = (2*fy/h) * (Y/Z) + (2*cy/h - 1)
    p.at(0, 0) = 2.0f * fx / width;
    p.at(0, 2) = 2.0f * cx / width - 1.0f;

    p.at(1, 1) = 2.0f * fy / height;
    p.at(1, 2) = 2.0f * cy / height - 1.0f;

    // Vulkan depth [0, 1]: z_ndc = far*(Z - near) / (Z*(far - near))
    p.at(2, 2) = -far / (far - near);
    p.at(2, 3) = -(far * near) / (far - near);

    p.at(3, 2) = -1.0f;  // perspective divide: w_clip = -Z_eye

    return p;
}

/**
 * 기본 원근 투영 (카메라 내재 파라미터가 없을 때 사용).
 * @param fovY 수직 FOV (radians)
 * @param aspect 종횡비 (width/height)
 */
inline Mat4 perspective(float fovY, float aspect,
                         float near = 0.05f, float far = 100.0f) {
    float tanHalf = tanf(fovY / 2.0f);

    Mat4 p;
    memset(p.m, 0, sizeof(p.m));

    p.at(0, 0) = 1.0f / (aspect * tanHalf);
    p.at(1, 1) = 1.0f / tanHalf;
    p.at(2, 2) = -far / (far - near);
    p.at(2, 3) = -(far * near) / (far - near);
    p.at(3, 2) = -1.0f;

    return p;
}

// ── 변환 유틸리티 ──

inline Mat4 translate(float x, float y, float z) {
    Mat4 r = Mat4::identity();
    r.at(0, 3) = x;
    r.at(1, 3) = y;
    r.at(2, 3) = z;
    return r;
}

inline Mat4 scale(float sx, float sy, float sz) {
    Mat4 r = Mat4::identity();
    r.at(0, 0) = sx;
    r.at(1, 1) = sy;
    r.at(2, 2) = sz;
    return r;
}

/**
 * Y축 회전 (radians).
 */
inline Mat4 rotateY(float angle) {
    float c = cosf(angle);
    float s = sinf(angle);
    Mat4 r = Mat4::identity();
    r.at(0, 0) = c;
    r.at(0, 2) = s;
    r.at(2, 0) = -s;
    r.at(2, 2) = c;
    return r;
}

} // namespace math
