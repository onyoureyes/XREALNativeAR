/**
 * vulkan_renderer_jni.cpp -- Kotlin ↔ Vulkan C++ JNI 브릿지.
 *
 * 패턴 참조: vio_jni.cpp (xreal-hardware-standalone)
 */

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <memory>
#include <atomic>

#include "vulkan_context.h"
#include "vulkan_pipeline.h"
#include "camera_background.h"
#include "mesh_renderer.h"

#define TAG "VulkanRendererJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::unique_ptr<VulkanContext> g_ctx;
static std::unique_ptr<VulkanPipeline> g_pipeline;
static std::unique_ptr<CameraBackground> g_cameraBg;
static std::unique_ptr<MeshRenderer> g_meshRenderer;
static ANativeWindow* g_window = nullptr;
static std::atomic<bool> g_initialized{false};

// ── JNI: nativeInit ──
extern "C" JNIEXPORT jint JNICALL
Java_com_xreal_vulkan_VulkanRendererBridge_nativeInit(
    JNIEnv* env, jobject thiz, jobject surface)
{
    LOGI("nativeInit called");

    if (g_initialized.load()) {
        LOGE("Already initialized, destroying first");
        g_pipeline.reset();
        g_ctx.reset();
        if (g_window) {
            ANativeWindow_release(g_window);
            g_window = nullptr;
        }
    }

    // Surface → ANativeWindow
    g_window = ANativeWindow_fromSurface(env, surface);
    if (!g_window) {
        LOGE("Failed to get ANativeWindow from Surface");
        return -1;
    }

    int width = ANativeWindow_getWidth(g_window);
    int height = ANativeWindow_getHeight(g_window);
    LOGI("Window size: %dx%d", width, height);

    // Vulkan 컨텍스트 초기화
    g_ctx = std::make_unique<VulkanContext>();
    if (!g_ctx->init(g_window)) {
        LOGE("Failed to initialize Vulkan context");
        g_ctx.reset();
        ANativeWindow_release(g_window);
        g_window = nullptr;
        return -2;
    }

    // 카메라 배경 초기화 (Phase 2)
    g_cameraBg = std::make_unique<CameraBackground>();
    if (!g_cameraBg->init(*g_ctx)) {
        LOGE("Failed to initialize camera background");
        g_cameraBg.reset();
        // 카메라 배경 실패해도 삼각형은 렌더링 가능 (non-fatal)
    }

    // 삼각형 파이프라인 생성 (Phase 1 폴백)
    g_pipeline = std::make_unique<VulkanPipeline>();
    if (!g_pipeline->createTrianglePipeline(*g_ctx)) {
        LOGE("Failed to create triangle pipeline");
        g_pipeline.reset();
        g_cameraBg.reset();
        g_ctx.reset();
        ANativeWindow_release(g_window);
        g_window = nullptr;
        return -3;
    }

    // 3D 메쉬 렌더러 초기화 (Phase 3: VIO 포즈 기반 3D 큐브)
    g_meshRenderer = std::make_unique<MeshRenderer>();
    if (!g_meshRenderer->init(*g_ctx)) {
        LOGE("Failed to initialize mesh renderer");
        g_meshRenderer.reset();
        // non-fatal: 삼각형은 계속 렌더링
    }

    g_initialized.store(true);
    LOGI("Vulkan renderer initialized successfully");
    return 0;
}

// ── JNI: nativeRenderFrame ──
extern "C" JNIEXPORT jint JNICALL
Java_com_xreal_vulkan_VulkanRendererBridge_nativeRenderFrame(
    JNIEnv* env, jobject thiz)
{
    if (!g_initialized.load() || !g_ctx || !g_pipeline) {
        return -1;
    }

    uint32_t imageIndex;
    if (!g_ctx->beginFrame(imageIndex)) {
        // 스왑체인 재생성 필요할 수 있음
        return -2;
    }

    VkCommandBuffer cmd = g_ctx->commandBuffer();

    // 1. 카메라 배경 (최하단 레이어, 깊이 쓰기 OFF)
    if (g_cameraBg) {
        g_cameraBg->draw(cmd);
    }

    // 2. 3D 메쉬 오브젝트 (Phase 3: VIO 포즈 기반)
    if (g_meshRenderer) {
        g_meshRenderer->draw(cmd);
    }

    // 3. 폴백 삼각형 (카메라 없고 포즈 없을 때만)
    if ((!g_cameraBg || !g_cameraBg->hasTexture()) && !g_meshRenderer) {
        g_pipeline->drawTriangle(cmd);
    }

    if (!g_ctx->endFrame(imageIndex)) {
        return -3;
    }

    return 0;
}

// ── JNI: nativeUploadCameraFrame ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_vulkan_VulkanRendererBridge_nativeUploadCameraFrame(
    JNIEnv* env, jobject thiz, jbyteArray data, jint size)
{
    if (!g_initialized.load() || !g_cameraBg) {
        return;
    }

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return;

    g_cameraBg->updateFrame(reinterpret_cast<const uint8_t*>(bytes), size);

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

// ── JNI: nativeSetPose ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_vulkan_VulkanRendererBridge_nativeSetPose(
    JNIEnv* env, jobject thiz,
    jfloat x, jfloat y, jfloat z,
    jfloat qx, jfloat qy, jfloat qz, jfloat qw)
{
    if (!g_initialized.load() || !g_meshRenderer) return;
    g_meshRenderer->setPose(x, y, z, qx, qy, qz, qw);
}

// ── JNI: nativeResize ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_vulkan_VulkanRendererBridge_nativeResize(
    JNIEnv* env, jobject thiz, jint width, jint height)
{
    LOGI("nativeResize: %dx%d", width, height);
    if (g_ctx && g_initialized.load()) {
        g_ctx->recreateSwapchain(width, height);
    }
}

// ── JNI: nativeDestroy ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_vulkan_VulkanRendererBridge_nativeDestroy(
    JNIEnv* env, jobject thiz)
{
    LOGI("nativeDestroy called");
    g_initialized.store(false);

    if (g_meshRenderer) {
        g_meshRenderer.reset();
    }
    if (g_cameraBg) {
        g_cameraBg.reset();
    }
    if (g_pipeline) {
        g_pipeline.reset();
    }
    if (g_ctx) {
        g_ctx.reset();
    }
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }

    LOGI("Vulkan renderer destroyed");
}
