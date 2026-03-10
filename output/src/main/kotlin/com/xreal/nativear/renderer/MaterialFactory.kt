package com.xreal.nativear.renderer

import com.xreal.nativear.core.XRealLogger
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.filamat.MaterialBuilder
import com.google.android.filament.filamat.MaterialPackage

/**
 * Runtime material compiler for Filament.
 * Uses filamat-android to compile materials at runtime (no matc tool needed).
 * Materials are compiled once and cached for the Engine lifetime.
 */
class MaterialFactory {
    companion object {
        private const val TAG = "MaterialFactory"
    }

    private var cameraBgMaterial: Material? = null
    private var ghostMaterial: Material? = null
    private var solidColorMaterial: Material? = null
    private var initialized = false

    fun init() {
        if (!initialized) {
            MaterialBuilder.init()
            initialized = true
            XRealLogger.impl.i(TAG, "MaterialBuilder initialized")
        }
    }

    fun shutdown() {
        if (initialized) {
            MaterialBuilder.shutdown()
            initialized = false
            XRealLogger.impl.i(TAG, "MaterialBuilder shutdown")
        }
    }

    /**
     * Unlit material with sampler2d texture for camera background.
     * No depth write, no depth test, opaque blending.
     */
    fun getCameraBgMaterial(engine: Engine): Material {
        cameraBgMaterial?.let { return it }

        val pkg = MaterialBuilder()
            .name("camera_bg")
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.ALL)
            .optimization(MaterialBuilder.Optimization.PERFORMANCE)
            .shading(MaterialBuilder.Shading.UNLIT)
            .blending(MaterialBuilder.BlendingMode.OPAQUE)
            .depthWrite(false)
            .depthCulling(false)
            .doubleSided(true)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
            .samplerParameter(
                MaterialBuilder.SamplerType.SAMPLER_2D,
                MaterialBuilder.SamplerFormat.FLOAT,
                MaterialBuilder.ParameterPrecision.DEFAULT,
                "cameraTexture"
            )
            .material("""
                void material(inout MaterialInputs material) {
                    prepareMaterial(material);
                    vec2 uv = getUV0();
                    // Flip Y for camera (camera image is top-down, GL is bottom-up)
                    uv.y = 1.0 - uv.y;
                    material.baseColor = texture(materialParams_cameraTexture, uv);
                }
            """.trimIndent())
            .build()

        if (pkg.isValid) {
            val data = pkg.buffer
            cameraBgMaterial = Material.Builder()
                .payload(data, data.remaining())
                .build(engine)
            XRealLogger.impl.i(TAG, "Camera background material compiled")
        } else {
            XRealLogger.impl.e(TAG, "Failed to compile camera background material")
        }

        return cameraBgMaterial!!
    }

    /**
     * Lit material with transparency for ghost runner.
     * Supports animPhase for pulse effect and baseColor with alpha.
     */
    fun getGhostMaterial(engine: Engine): Material {
        ghostMaterial?.let { return it }

        val pkg = MaterialBuilder()
            .name("ghost_runner")
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.ALL)
            .optimization(MaterialBuilder.Optimization.PERFORMANCE)
            .shading(MaterialBuilder.Shading.LIT)
            .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
            .depthWrite(false)
            .depthCulling(true)
            .doubleSided(true)
            .transparencyMode(MaterialBuilder.TransparencyMode.TWO_PASSES_TWO_SIDES)
            .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "ghostColor")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "animPhase")
            .material("""
                void material(inout MaterialInputs material) {
                    prepareMaterial(material);

                    float pulse = 0.5 + 0.5 * sin(materialParams.animPhase * 6.28318);

                    vec4 color = materialParams.ghostColor;
                    color.a *= (0.35 + 0.15 * pulse);

                    material.baseColor = color;
                    material.emissive = vec4(color.rgb * (0.3 + 0.2 * pulse), 0.0);
                    material.metallic = 0.0;
                    material.roughness = 0.7;
                }
            """.trimIndent())
            .build()

        if (pkg.isValid) {
            val data = pkg.buffer
            ghostMaterial = Material.Builder()
                .payload(data, data.remaining())
                .build(engine)
            XRealLogger.impl.i(TAG, "Ghost runner material compiled")
        } else {
            XRealLogger.impl.e(TAG, "Failed to compile ghost runner material")
        }

        return ghostMaterial!!
    }

    /**
     * Simple solid color unlit material for test objects (cube, etc.)
     */
    fun getSolidColorMaterial(engine: Engine): Material {
        solidColorMaterial?.let { return it }

        val pkg = MaterialBuilder()
            .name("solid_color")
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(MaterialBuilder.TargetApi.ALL)
            .optimization(MaterialBuilder.Optimization.PERFORMANCE)
            .shading(MaterialBuilder.Shading.UNLIT)
            .blending(MaterialBuilder.BlendingMode.OPAQUE)
            .depthWrite(true)
            .depthCulling(true)
            .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "color")
            .material("""
                void material(inout MaterialInputs material) {
                    prepareMaterial(material);
                    material.baseColor = materialParams.color;
                }
            """.trimIndent())
            .build()

        if (pkg.isValid) {
            val data = pkg.buffer
            solidColorMaterial = Material.Builder()
                .payload(data, data.remaining())
                .build(engine)
            XRealLogger.impl.i(TAG, "Solid color material compiled")
        } else {
            XRealLogger.impl.e(TAG, "Failed to compile solid color material")
        }

        return solidColorMaterial!!
    }

    fun destroyAll(engine: Engine) {
        cameraBgMaterial?.let { engine.destroyMaterial(it) }
        ghostMaterial?.let { engine.destroyMaterial(it) }
        solidColorMaterial?.let { engine.destroyMaterial(it) }
        cameraBgMaterial = null
        ghostMaterial = null
        solidColorMaterial = null
    }
}
