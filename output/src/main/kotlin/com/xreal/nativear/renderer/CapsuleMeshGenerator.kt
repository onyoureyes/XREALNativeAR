package com.xreal.nativear.renderer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 프로시저럴 캡슐 메시 정점/인덱스 생성기.
 *
 * Filament 의존 없이 순수 기하학 데이터(정점 좌표 + 법선 + 인덱스)만 생성.
 * GhostRunnerEntity에서 Filament VertexBuffer/IndexBuffer 생성에 사용.
 *
 * 정점 레이아웃: [x, y, z, nx, ny, nz] (stride = 6 floats)
 */
object CapsuleMeshGenerator {

    /** 생성된 메시 데이터 */
    data class MeshData(val vertices: FloatArray, val indices: ShortArray) {
        /** 정점 수 (각 정점은 6 float: x,y,z,nx,ny,nz) */
        val vertexCount: Int get() = vertices.size / 6

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MeshData) return false
            return vertices.contentEquals(other.vertices) && indices.contentEquals(other.indices)
        }

        override fun hashCode(): Int {
            return 31 * vertices.contentHashCode() + indices.contentHashCode()
        }
    }

    /**
     * 캡슐 메시를 생성한다.
     *
     * 캡슐 = 하부 반구 + 원통 + 상부 반구
     * 캡슐 중심이 원점, Y축이 위/아래.
     *
     * @param radius 캡슐 반지름 (기본 0.2m)
     * @param bodyHeight 원통 부분 높이 (기본 1.2m)
     * @param segments 원주 분할 수 (기본 24)
     * @param rings 반구 링 수 (기본 8)
     * @return 정점 + 인덱스 데이터
     */
    fun generate(
        radius: Float = 0.2f,
        bodyHeight: Float = 1.2f,
        segments: Int = 24,
        rings: Int = 8
    ): MeshData {
        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        // 하부 반구
        for (ring in 0..rings) {
            val phi = (PI / 2.0) * ring / rings
            val ringRadius = radius * cos(phi).toFloat()
            val ringY = -bodyHeight / 2f - radius * sin(phi).toFloat()

            for (seg in 0..segments) {
                val theta = 2.0 * PI * seg / segments
                val x = ringRadius * cos(theta).toFloat()
                val z = ringRadius * sin(theta).toFloat()

                val nx = cos(phi).toFloat() * cos(theta).toFloat()
                val ny = -sin(phi).toFloat()
                val nz = cos(phi).toFloat() * sin(theta).toFloat()

                vertices.addAll(listOf(x, ringY, z, nx, ny, nz))
            }
        }

        val bottomVertCount = (rings + 1) * (segments + 1)

        // 원통 몸체 (2개 링)
        for (i in 0..1) {
            val y = if (i == 0) -bodyHeight / 2f else bodyHeight / 2f
            for (seg in 0..segments) {
                val theta = 2.0 * PI * seg / segments
                val x = radius * cos(theta).toFloat()
                val z = radius * sin(theta).toFloat()
                val nx = cos(theta).toFloat()
                val nz = sin(theta).toFloat()
                vertices.addAll(listOf(x, y, z, nx, 0f, nz))
            }
        }

        val cylinderStart = bottomVertCount

        // 상부 반구
        val topStart = cylinderStart + 2 * (segments + 1)
        for (ring in 0..rings) {
            val phi = (PI / 2.0) * ring / rings
            val ringRadius = radius * cos(phi).toFloat()
            val ringY = bodyHeight / 2f + radius * sin(phi).toFloat()

            for (seg in 0..segments) {
                val theta = 2.0 * PI * seg / segments
                val x = ringRadius * cos(theta).toFloat()
                val z = ringRadius * sin(theta).toFloat()

                val nx = cos(phi).toFloat() * cos(theta).toFloat()
                val ny = sin(phi).toFloat()
                val nz = cos(phi).toFloat() * sin(theta).toFloat()

                vertices.addAll(listOf(x, ringY, z, nx, ny, nz))
            }
        }

        // 인덱스 생성

        // 하부 반구 삼각형
        for (ring in 0 until rings) {
            for (seg in 0 until segments) {
                val curr = ring * (segments + 1) + seg
                val next = curr + segments + 1

                indices.add(curr.toShort())
                indices.add(next.toShort())
                indices.add((curr + 1).toShort())

                indices.add((curr + 1).toShort())
                indices.add(next.toShort())
                indices.add((next + 1).toShort())
            }
        }

        // 원통 삼각형
        for (seg in 0 until segments) {
            val a = cylinderStart + seg
            val b = cylinderStart + segments + 1 + seg

            indices.add(a.toShort())
            indices.add(b.toShort())
            indices.add((a + 1).toShort())

            indices.add((a + 1).toShort())
            indices.add(b.toShort())
            indices.add((b + 1).toShort())
        }

        // 상부 반구 삼각형
        for (ring in 0 until rings) {
            for (seg in 0 until segments) {
                val curr = topStart + ring * (segments + 1) + seg
                val next = curr + segments + 1

                indices.add(curr.toShort())
                indices.add((curr + 1).toShort())
                indices.add(next.toShort())

                indices.add((curr + 1).toShort())
                indices.add((next + 1).toShort())
                indices.add(next.toShort())
            }
        }

        return MeshData(
            vertices = vertices.toFloatArray(),
            indices = indices.toShortArray()
        )
    }
}
