package com.xreal.nativear.renderer

import com.xreal.nativear.core.XRealLogger
import com.google.android.filament.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural capsule mesh entity for the ghost runner pacemaker.
 * Semi-transparent with pulse animation effect.
 *
 * Capsule: radius 0.2m, height 1.6m, 24 longitudinal segments
 * Color changes based on pace difference (green = ahead, red = behind)
 */
class GhostRunnerEntity(
    private val engine: Engine,
    private val scene: Scene,
    private val materialFactory: MaterialFactory = MaterialFactory()
) {
    companion object {
        private const val TAG = "GhostRunnerEntity"
        private const val RADIUS = 0.2f
        private const val BODY_HEIGHT = 1.2f  // cylinder part
        private const val TOTAL_HEIGHT = BODY_HEIGHT + 2 * RADIUS  // 1.6m
        private const val SEGMENTS = 24
        private const val RINGS = 8  // hemisphere rings
    }

    private var entity: Int = 0
    private var vertexBuffer: VertexBuffer? = null
    private var indexBuffer: IndexBuffer? = null
    private var materialInstance: MaterialInstance? = null
    private var visible = false
    private var animPhase = 0f
    private val startTime = System.nanoTime()

    // Current color (default green)
    private var colorR = 0.2f
    private var colorG = 1.0f
    private var colorB = 0.4f
    private var colorA = 0.6f

    init {
        createCapsuleMesh()
    }

    /**
     * Set ghost runner world position and yaw.
     * worldY is offset so the capsule bottom sits at the ground plane.
     */
    fun setPose(worldX: Float, worldY: Float, worldZ: Float, yaw: Float = 0f) {
        if (entity == 0) return

        val tcm = engine.transformManager
        val instance = tcm.getInstance(entity)
        if (instance == 0) return

        // Build transform: translate + rotate around Y axis
        val cy = cos(yaw.toDouble())
        val sy = sin(yaw.toDouble())

        // Column-major 4x4 (Filament convention)
        // Offset Y by TOTAL_HEIGHT/2 so capsule bottom sits at worldY
        tcm.setTransform(instance, doubleArrayOf(
            cy,  0.0, -sy, 0.0,    // column 0
            0.0, 1.0, 0.0, 0.0,    // column 1
            sy,  0.0, cy,  0.0,    // column 2
            worldX.toDouble(), (worldY + TOTAL_HEIGHT / 2.0), worldZ.toDouble(), 1.0  // column 3
        ))
    }

    fun setVisible(show: Boolean) {
        if (show == visible) return
        visible = show
        if (show) {
            scene.addEntity(entity)
        } else {
            scene.removeEntity(entity)
        }
    }

    fun setColor(r: Float, g: Float, b: Float, a: Float) {
        colorR = r; colorG = g; colorB = b; colorA = a
        materialInstance?.setParameter("ghostColor", colorR, colorG, colorB, colorA)
    }

    /**
     * Update pulse animation phase. Called each frame from FilamentRenderer.
     */
    fun updateAnimation(engine: Engine) {
        if (!visible) return
        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0f
        animPhase = elapsed
        materialInstance?.setParameter("animPhase", animPhase)
    }

    fun destroy(engine: Engine) {
        if (visible) scene.removeEntity(entity)
        if (entity != 0) {
            engine.destroyEntity(entity)
            EntityManager.get().destroy(entity)
        }
        vertexBuffer?.let { engine.destroyVertexBuffer(it) }
        indexBuffer?.let { engine.destroyIndexBuffer(it) }
        materialInstance?.let { engine.destroyMaterialInstance(it) }
        entity = 0
        vertexBuffer = null
        indexBuffer = null
        materialInstance = null
    }

    // ========== Procedural Capsule Generation ==========

    private fun createCapsuleMesh() {
        // 정점/인덱스 생성을 CapsuleMeshGenerator로 위임
        val meshData = CapsuleMeshGenerator.generate(
            radius = RADIUS,
            bodyHeight = BODY_HEIGHT,
            segments = SEGMENTS,
            rings = RINGS
        )
        val vertices = meshData.vertices
        val indices = meshData.indices

        // Build Filament buffers
        val vertexCount = meshData.vertexCount
        val vertexData = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
        vertices.forEach { vertexData.putFloat(it) }
        vertexData.flip()

        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                VertexBuffer.AttributeType.FLOAT3, 0, 24)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0,
                VertexBuffer.AttributeType.FLOAT3, 12, 24)
            .normalized(VertexBuffer.VertexAttribute.TANGENTS)
            .build(engine)
        vertexBuffer!!.setBufferAt(engine, 0, vertexData)

        val indexData = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
        indices.forEach { indexData.putShort(it) }
        indexData.flip()

        indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer!!.setBuffer(engine, indexData)

        // Create material instance
        val material = materialFactory.getGhostMaterial(engine)
        materialInstance = material.createInstance().apply {
            setParameter("ghostColor", colorR, colorG, colorB, colorA)
            setParameter("animPhase", 0f)
        }

        // Create entity with transform
        entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(
                -RADIUS, -TOTAL_HEIGHT / 2f, -RADIUS,
                RADIUS, TOTAL_HEIGHT / 2f, RADIUS
            ))
            .material(0, materialInstance!!)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer!!, indexBuffer!!)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(4) // After camera background (7), before UI
            .build(engine, entity)

        // Add TransformManager component
        val tcm = engine.transformManager
        tcm.create(entity)

        XRealLogger.impl.i(TAG, "Ghost runner capsule created: $vertexCount vertices, ${indices.size} indices")
    }
}
