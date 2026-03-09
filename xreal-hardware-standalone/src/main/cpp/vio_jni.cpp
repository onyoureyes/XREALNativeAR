#include <android/log.h>
#include <jni.h>
#include <memory>

#include "vio_bridge.h"

#define TAG "VIOBridgeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::unique_ptr<VIOBridge> g_vio;
static JavaVM *g_jvm = nullptr;
static jobject g_callbackObj = nullptr;
static jmethodID g_onPoseMethod = nullptr;

// ── JNI: Init ──
extern "C" JNIEXPORT jint JNICALL
Java_com_xreal_hardware_VIOManager_nativeInit(JNIEnv *env, jobject thiz) {
    LOGI("nativeInit");

    // 이전 인스턴스가 남아있으면 먼저 정리 (앱 재시작 시 mutex 충돌 방지)
    if (g_vio) {
        LOGI("Cleaning up previous VIOBridge instance before re-init");
        g_vio.reset();
    }

    env->GetJavaVM(&g_jvm);

    // 이전 글로벌 레퍼런스 정리
    if (g_callbackObj) {
        env->DeleteGlobalRef(g_callbackObj);
        g_callbackObj = nullptr;
    }
    g_callbackObj = env->NewGlobalRef(thiz);

    jclass clazz = env->GetObjectClass(thiz);
    g_onPoseMethod = env->GetMethodID(clazz, "onNativePose", "(FFFFFFFD)V");
    if (!g_onPoseMethod) {
        LOGE("onNativePose method not found!");
        return -1;
    }

    // VIOBridge 생성 및 초기화 — C++ 예외 보호
    try {
        auto bridge = std::make_unique<VIOBridge>();
        if (!bridge->initialize()) {
            LOGE("VIOBridge::initialize() returned false — skipping VIO");
            return -2;
        }
        g_vio = std::move(bridge);
    } catch (const std::exception &e) {
        LOGE("VIOBridge::initialize() threw exception: %s — skipping VIO", e.what());
        return -3;
    } catch (...) {
        LOGE("VIOBridge::initialize() threw unknown exception — skipping VIO");
        return -3;
    }

    // Register pose callback → calls Java onNativePose()
    g_vio->setPoseCallback([](const VIOBridge::Pose6DoF &pose) {
        if (!g_jvm || !g_callbackObj || !g_onPoseMethod) return;

        JNIEnv *env = nullptr;
        bool attached = false;
        int status = g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Failed to attach thread for pose callback");
                return;
            }
            attached = true;
        }

        env->CallVoidMethod(g_callbackObj, g_onPoseMethod,
                            (jfloat)pose.x, (jfloat)pose.y, (jfloat)pose.z,
                            (jfloat)pose.qx, (jfloat)pose.qy, (jfloat)pose.qz,
                            (jfloat)pose.qw, (jdouble)pose.timestamp);

        if (attached) {
            g_jvm->DetachCurrentThread();
        }
    });

    LOGI("nativeInit complete");
    return 0;
}

// ── JNI: Feed IMU ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_hardware_VIOManager_nativeFeedIMU(
    JNIEnv *env, jobject thiz,
    jfloat gx, jfloat gy, jfloat gz,
    jfloat ax, jfloat ay, jfloat az,
    jlong timestamp_us) {

    if (!g_vio) return;
    double ts_sec = timestamp_us / 1e6;
    g_vio->feedIMU(gx, gy, gz, ax, ay, az, ts_sec);
}

// ── JNI: Feed Stereo Frame ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_hardware_VIOManager_nativeFeedStereoFrame(
    JNIEnv *env, jobject thiz,
    jbyteArray leftArr, jbyteArray rightArr,
    jint width, jint height,
    jlong timestamp_us) {

    if (!g_vio) return;

    // Use GetPrimitiveArrayCritical for zero-copy access
    jbyte *leftPtr = (jbyte *)env->GetPrimitiveArrayCritical(leftArr, nullptr);
    jbyte *rightPtr = (jbyte *)env->GetPrimitiveArrayCritical(rightArr, nullptr);

    if (leftPtr && rightPtr) {
        double ts_sec = timestamp_us / 1e6;
        g_vio->feedStereoFrame(
            reinterpret_cast<const uint8_t *>(leftPtr),
            reinterpret_cast<const uint8_t *>(rightPtr),
            width, height, ts_sec);
    }

    if (rightPtr) env->ReleasePrimitiveArrayCritical(rightArr, rightPtr, JNI_ABORT);
    if (leftPtr) env->ReleasePrimitiveArrayCritical(leftArr, leftPtr, JNI_ABORT);
}

// ── JNI: Start ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_hardware_VIOManager_nativeStart(JNIEnv *env, jobject thiz) {
    LOGI("nativeStart");
    if (g_vio) g_vio->start();
}

// ── JNI: Stop ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_hardware_VIOManager_nativeStop(JNIEnv *env, jobject thiz) {
    LOGI("nativeStop");
    if (g_vio) g_vio->stop();
}

// ── JNI: Release ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_hardware_VIOManager_nativeRelease(JNIEnv *env, jobject thiz) {
    LOGI("nativeRelease");
    g_vio.reset();

    if (g_callbackObj) {
        env->DeleteGlobalRef(g_callbackObj);
        g_callbackObj = nullptr;
    }
    g_onPoseMethod = nullptr;
}

// ── JNI: isInitialized ──
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xreal_hardware_VIOManager_nativeIsInitialized(JNIEnv *env, jobject thiz) {
    return g_vio ? g_vio->isInitialized() : false;
}
