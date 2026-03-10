package com.xreal.nativear.renderer

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.sqrt

/**
 * CapsuleMeshGenerator 단위 테스트.
 * 프로시저럴 캡슐 메시의 정점/인덱스 유효성 검증.
 */
class CapsuleMeshGeneratorTest {

    private val defaultMesh = CapsuleMeshGenerator.generate()

    @Test
    fun `정점 수가 0보다 크다`() {
        assertTrue("정점 수는 양수여야 함", defaultMesh.vertexCount > 0)
    }

    @Test
    fun `인덱스 수가 0보다 크다`() {
        assertTrue("인덱스 수는 양수여야 함", defaultMesh.indices.isNotEmpty())
    }

    @Test
    fun `정점 배열 크기는 vertexCount의 6배`() {
        assertEquals(defaultMesh.vertexCount * 6, defaultMesh.vertices.size)
    }

    @Test
    fun `인덱스 수는 3의 배수 (삼각형)`() {
        assertEquals("삼각형이므로 인덱스는 3의 배수", 0, defaultMesh.indices.size % 3)
    }

    @Test
    fun `모든 인덱스가 정점 범위 내`() {
        val maxIndex = defaultMesh.vertexCount
        for (i in defaultMesh.indices.indices) {
            val idx = defaultMesh.indices[i].toInt() and 0xFFFF  // unsigned short
            assertTrue(
                "인덱스[$i] = $idx 는 정점 범위 [0, $maxIndex) 내여야 함",
                idx in 0 until maxIndex
            )
        }
    }

    @Test
    fun `법선 벡터 정규화 검증`() {
        // 각 정점의 법선 (stride 6, offset 3)이 대략 길이 1.0
        val vertices = defaultMesh.vertices
        var checkedCount = 0
        for (v in 0 until defaultMesh.vertexCount) {
            val base = v * 6
            val nx = vertices[base + 3]
            val ny = vertices[base + 4]
            val nz = vertices[base + 5]
            val length = sqrt((nx * nx + ny * ny + nz * nz).toDouble())

            // 극점 근처는 수치 오차 가능, 그래도 0.9~1.1 범위 확인
            assertTrue(
                "정점[$v] 법선 길이 $length 은 약 1.0이어야 함",
                length in 0.9..1.1
            )
            checkedCount++
        }
        assertTrue("최소 1개 이상 법선 검증", checkedCount > 0)
    }

    @Test
    fun `정점 좌표가 합리적 범위 내`() {
        // 기본 파라미터: radius=0.2, bodyHeight=1.2 → totalHeight=1.6
        // 좌표 범위: x,z ∈ [-0.2, 0.2], y ∈ [-0.8, 0.8]
        val maxExtent = 2.0f  // 넉넉한 범위
        val vertices = defaultMesh.vertices
        for (v in 0 until defaultMesh.vertexCount) {
            val base = v * 6
            val x = vertices[base]
            val y = vertices[base + 1]
            val z = vertices[base + 2]

            assertTrue("x=$x 범위 초과", x in -maxExtent..maxExtent)
            assertTrue("y=$y 범위 초과", y in -maxExtent..maxExtent)
            assertTrue("z=$z 범위 초과", z in -maxExtent..maxExtent)
        }
    }

    @Test
    fun `커스텀 파라미터로 메시 생성 가능`() {
        val mesh = CapsuleMeshGenerator.generate(
            radius = 0.5f,
            bodyHeight = 2.0f,
            segments = 12,
            rings = 4
        )
        assertTrue(mesh.vertexCount > 0)
        assertTrue(mesh.indices.isNotEmpty())
    }

    @Test
    fun `segments 변경 시 정점 수 변화`() {
        val mesh12 = CapsuleMeshGenerator.generate(segments = 12)
        val mesh24 = CapsuleMeshGenerator.generate(segments = 24)
        assertTrue(
            "세그먼트 수가 클수록 정점 수가 많아야 함",
            mesh24.vertexCount > mesh12.vertexCount
        )
    }

    @Test
    fun `rings 변경 시 정점 수 변화`() {
        val mesh4 = CapsuleMeshGenerator.generate(rings = 4)
        val mesh8 = CapsuleMeshGenerator.generate(rings = 8)
        assertTrue(
            "반구 링 수가 클수록 정점 수가 많아야 함",
            mesh8.vertexCount > mesh4.vertexCount
        )
    }

    @Test
    fun `기본 파라미터 정점 수 계산 검증`() {
        // 하부 반구: (rings+1) * (segments+1) = 9 * 25 = 225
        // 원통: 2 * (segments+1) = 2 * 25 = 50
        // 상부 반구: (rings+1) * (segments+1) = 9 * 25 = 225
        // 합계: 500
        assertEquals(500, defaultMesh.vertexCount)
    }

    @Test
    fun `기본 파라미터 인덱스 수 계산 검증`() {
        // 하부 반구 삼각형: rings * segments * 6 = 8 * 24 * 6 = 1152
        // 원통 삼각형: segments * 6 = 24 * 6 = 144
        // 상부 반구 삼각형: rings * segments * 6 = 8 * 24 * 6 = 1152
        // 합계: 2448
        assertEquals(2448, defaultMesh.indices.size)
    }

    @Test
    fun `MeshData vertexCount 프로퍼티 정확성`() {
        val mesh = CapsuleMeshGenerator.generate(segments = 6, rings = 2)
        assertEquals(mesh.vertices.size / 6, mesh.vertexCount)
    }
}
