package com.xreal.nativear.renderer

import android.graphics.BitmapFactory
import com.xreal.nativear.core.XRealLogger
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels

/**
 * Filament-based 3D renderer for XREAL AR glasses.
 * Replaces the custom Vulkan C++ renderer with pure Kotlin.
 *
 * Architecture:
 * - Single Scene with camera background quad + 3D objects
 * - Camera driven by OpenVINS VIO pose (HeadPoseUpdated events)
 * - Camera background: MJPEG → BitmapFactory → Filament Texture
 * - Ghost runner: procedural capsule mesh with transparent material
 */
class FilamentRenderer(private val surfaceView: SurfaceView) {
    companion object {
        private const val TAG = "FilamentRenderer"
        init { Filament.init() }

        // RGB Camera intrinsics (from CameraModel.kt)
        private const val FX = 914.0
        private const val FY = 914.0
        private const val CX = 640.0
        private const val CY = 480.0
        private const val IMG_W = 1280.0
        private const val IMG_H = 960.0
        private const val NEAR = 0.05
        private const val FAR = 100.0
    }

    // Filament core objects
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper
    // UiHelper 콜백(main thread)과 doFrame(render thread) 양쪽 접근 → Volatile 필수
    @Volatile private var swapChain: SwapChain? = null

    // Camera background
    private var cameraTexture: Texture? = null
    private var cameraMaterialInstance: MaterialInstance? = null
    private var cameraQuadEntity: Int = 0
    private var cameraQuadVertexBuffer: VertexBuffer? = null
    private var cameraQuadIndexBuffer: IndexBuffer? = null
    private val cameraFrameLock = Object()
    @Volatile private var pendingCameraFrame: ByteArray? = null
    private var cameraTextureWidth = 0
    private var cameraTextureHeight = 0
    private var cameraTextureReady = false

    // Material factory (lifecycle tied to this renderer)
    private val materialFactory = MaterialFactory()

    // Ghost runner
    var ghostRunner: GhostRunnerEntity? = null
        private set

    // Render loop
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = FrameCallback()
    private var setupComplete = false

    // Pose state
    @Volatile private var poseValid = false
    private val poseModelMatrix = DoubleArray(16)  // camera-to-world (column-major)
    private val projectionMatrix = DoubleArray(16) // from RGB intrinsics
    private var projectionComputed = false

    fun setup() {
        XRealLogger.impl.i(TAG, "Setting up Filament renderer")

        materialFactory.init()

        // Create engine (OpenGL ES backend for camera texture compatibility)
        engine = Engine.create()
        displayHelper = DisplayHelper(surfaceView.context)
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()

        // Create camera
        val cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)

        // Configure view
        view.camera = camera
        view.scene = scene

        // Transparent clear for AR overlay
        view.blendMode = View.BlendMode.TRANSLUCENT
        renderer.clearOptions = renderer.clearOptions.apply {
            clear = true
        }

        // Add directional light for ghost runner
        val sunEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(80_000f)
            .direction(0.0f, -1.0f, -0.5f)
            .castShadows(false)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        // Ambient light
        scene.indirectLight = IndirectLight.Builder()
            .intensity(20_000f)
            .build(engine)

        // Compute projection matrix from RGB camera intrinsics (once)
        computeProjectionMatrix()

        // UiHelper manages Surface lifecycle
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            isOpaque = true  // Camera background fills entire surface
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
                    displayHelper.attach(renderer, surfaceView.display)
                }

                override fun onDetachedFromSurface() {
                    displayHelper.detach()
                    swapChain?.let {
                        engine.destroySwapChain(it)
                        engine.flushAndWait()
                    }
                    swapChain = null
                }

                override fun onResized(width: Int, height: Int) {
                    view.viewport = Viewport(0, 0, width, height)
                    XRealLogger.impl.d(TAG, "Viewport resized: ${width}x${height}")
                }
            }
            attachTo(surfaceView)
        }

        // Create ghost runner entity
        ghostRunner = GhostRunnerEntity(engine, scene, materialFactory)

        setupComplete = true
        choreographer.postFrameCallback(frameCallback)
        XRealLogger.impl.i(TAG, "Filament renderer setup complete")
    }

    /**
     * Upload MJPEG camera frame (called from camera thread in HardwareManager).
     * Decoded and uploaded to texture on the render thread.
     */
    fun uploadCameraFrame(mjpegData: ByteArray) {
        synchronized(cameraFrameLock) {
            pendingCameraFrame = mjpegData
        }
    }

    /**
     * Set VIO pose (camera-to-world transform).
     * Called from FilamentSceneManager on EventBus coroutine.
     */
    fun setPose(x: Float, y: Float, z: Float,
                qx: Float, qy: Float, qz: Float, qw: Float) {
        // 쿼터니언 → 행렬 변환 (QuaternionMatrixConverter로 위임)
        val matrix = QuaternionMatrixConverter.toMatrix(x, y, z, qx, qy, qz, qw)

        synchronized(poseModelMatrix) {
            System.arraycopy(matrix, 0, poseModelMatrix, 0, 16)
            poseValid = true
        }
    }

    fun onPause() {
        choreographer.removeFrameCallback(frameCallback)
    }

    fun onResume() {
        if (setupComplete) {
            choreographer.postFrameCallback(frameCallback)
        }
    }

    fun destroy() {
        XRealLogger.impl.i(TAG, "Destroying Filament renderer")
        choreographer.removeFrameCallback(frameCallback)

        ghostRunner?.destroy(engine)
        ghostRunner = null

        destroyCameraBackground()

        materialFactory.destroyAll(engine)

        uiHelper.detach()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)
        engine.destroy()

        materialFactory.shutdown()
        XRealLogger.impl.i(TAG, "Filament renderer destroyed")
    }

    // ========== Private Implementation ==========

    /**
     * Compute projection matrix from RGB camera intrinsics.
     * OpenCV camera model → Filament projection (column-major).
     */
    private fun computeProjectionMatrix() {
        // Standard pinhole: fx, fy, cx, cy → NDC projection
        // Filament uses column-major, OpenGL-style NDC [-1,1] for XY, [0,1] for Z
        val l = -NEAR * CX / FX
        val r = NEAR * (IMG_W - CX) / FX
        val b = -NEAR * (IMG_H - CY) / FY
        val t = NEAR * CY / FY

        // Asymmetric frustum (column-major)
        projectionMatrix.fill(0.0)
        projectionMatrix[0]  = 2.0 * NEAR / (r - l)       // [0][0]
        projectionMatrix[5]  = 2.0 * NEAR / (t - b)       // [1][1]
        projectionMatrix[8]  = (r + l) / (r - l)          // [2][0]
        projectionMatrix[9]  = (t + b) / (t - b)          // [2][1]
        projectionMatrix[10] = -(FAR + NEAR) / (FAR - NEAR) // [2][2]
        projectionMatrix[11] = -1.0                        // [2][3]
        projectionMatrix[14] = -2.0 * FAR * NEAR / (FAR - NEAR) // [3][2]

        projectionComputed = true
    }

    /**
     * Process pending camera frame: decode MJPEG → upload to Filament texture.
     * Called on render thread.
     */
    private fun processCameraFrame() {
        val frameData: ByteArray
        synchronized(cameraFrameLock) {
            frameData = pendingCameraFrame ?: return
            pendingCameraFrame = null
        }

        try {
            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size) ?: return
            val w = bitmap.width
            val h = bitmap.height

            // Create or resize texture if needed
            if (cameraTexture == null || w != cameraTextureWidth || h != cameraTextureHeight) {
                createCameraBackground(w, h)
                cameraTextureWidth = w
                cameraTextureHeight = h
            }

            // Bitmap → ByteBuffer (RGBA)
            val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(buffer)
            buffer.flip()
            bitmap.recycle()

            // Upload to Filament texture
            cameraTexture?.setImage(engine, 0,
                Texture.PixelBufferDescriptor(
                    buffer,
                    Texture.Format.RGBA,
                    Texture.Type.UBYTE
                )
            )
            cameraTextureReady = true
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Camera frame decode error: ${e.message}")
        }
    }

    /**
     * Create camera background: texture + fullscreen quad entity.
     */
    private fun createCameraBackground(width: Int, height: Int) {
        // Clean up previous
        destroyCameraBackground()

        // Create texture
        cameraTexture = Texture.Builder()
            .width(width)
            .height(height)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .build(engine)

        // Get material
        val material = materialFactory.getCameraBgMaterial(engine)
        cameraMaterialInstance = material.createInstance().apply {
            setParameter("cameraTexture", cameraTexture!!,
                TextureSampler(
                    TextureSampler.MinFilter.LINEAR,
                    TextureSampler.MagFilter.LINEAR,
                    TextureSampler.WrapMode.CLAMP_TO_EDGE
                )
            )
        }

        // Create fullscreen quad (positioned at z = -NEAR - 0.001 in camera space)
        // We use a large quad covering the entire frustum at a fixed depth
        val depth = (NEAR + 0.001).toFloat()
        val halfW = (depth * IMG_W / (2.0 * FX)).toFloat()
        val halfH = (depth * IMG_H / (2.0 * FY)).toFloat()
        val cx_off = (depth * (CX - IMG_W / 2.0) / FX).toFloat()
        val cy_off = (depth * (CY - IMG_H / 2.0) / FY).toFloat()

        // Quad vertices (position + UV) in camera space
        // Camera space: X=right, Y=up (Filament convention), Z=-forward
        val vertices = floatArrayOf(
            // x,              y,              z,     u,   v
            -halfW + cx_off,  halfH - cy_off, -depth, 0f, 0f,  // top-left
             halfW + cx_off,  halfH - cy_off, -depth, 1f, 0f,  // top-right
            -halfW + cx_off, -halfH - cy_off, -depth, 0f, 1f,  // bottom-left
             halfW + cx_off, -halfH - cy_off, -depth, 1f, 1f,  // bottom-right
        )

        val vertexData = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
        vertices.forEach { vertexData.putFloat(it) }
        vertexData.flip()

        cameraQuadVertexBuffer = VertexBuffer.Builder()
            .vertexCount(4)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                VertexBuffer.AttributeType.FLOAT3, 0, 20)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0,
                VertexBuffer.AttributeType.FLOAT2, 12, 20)
            .build(engine)
        cameraQuadVertexBuffer!!.setBufferAt(engine, 0, vertexData)

        val indices = shortArrayOf(0, 2, 1, 1, 2, 3)
        val indexData = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
        indices.forEach { indexData.putShort(it) }
        indexData.flip()

        cameraQuadIndexBuffer = IndexBuffer.Builder()
            .indexCount(6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        cameraQuadIndexBuffer!!.setBuffer(engine, indexData)

        // Create entity
        cameraQuadEntity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(-halfW, -halfH, -depth - 0.1f, halfW, halfH, -depth + 0.1f))
            .material(0, cameraMaterialInstance!!)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES,
                cameraQuadVertexBuffer!!, cameraQuadIndexBuffer!!)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(7) // Render first (highest priority = rendered first in Filament)
            .build(engine, cameraQuadEntity)

        scene.addEntity(cameraQuadEntity)

        XRealLogger.impl.i(TAG, "Camera background created: ${width}x${height}")
    }

    private fun destroyCameraBackground() {
        if (cameraQuadEntity != 0) {
            scene.removeEntity(cameraQuadEntity)
            engine.destroyEntity(cameraQuadEntity)
            EntityManager.get().destroy(cameraQuadEntity)
            cameraQuadEntity = 0
        }
        cameraQuadVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        cameraQuadIndexBuffer?.let { engine.destroyIndexBuffer(it) }
        cameraMaterialInstance?.let { engine.destroyMaterialInstance(it) }
        cameraTexture?.let { engine.destroyTexture(it) }
        cameraQuadVertexBuffer = null
        cameraQuadIndexBuffer = null
        cameraMaterialInstance = null
        cameraTexture = null
        cameraTextureReady = false
    }

    /**
     * Update camera from VIO pose on the render thread.
     */
    private fun updateCamera() {
        if (!projectionComputed) return

        camera.setCustomProjection(projectionMatrix, NEAR, FAR)

        if (poseValid) {
            synchronized(poseModelMatrix) {
                camera.setModelMatrix(poseModelMatrix)
            }
        }
    }

    inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!setupComplete) return
            choreographer.postFrameCallback(this)

            // Process camera frame on render thread
            processCameraFrame()

            // Update camera pose
            updateCamera()

            // Update ghost runner animation
            ghostRunner?.updateAnimation(engine)

            // Render
            if (uiHelper.isReadyToRender) {
                val sc = swapChain ?: return
                if (renderer.beginFrame(sc, frameTimeNanos)) {
                    renderer.render(view)
                    renderer.endFrame()
                }
            }
        }
    }
}
