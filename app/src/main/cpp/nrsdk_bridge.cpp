#include "nrsdk_bridge.h"
#include <android/log.h>
#include <jni.h>

#define LOG_TAG "NRSDKBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace xreal {

static glm::mat4 s_headPose = glm::mat4(1.0f); // Identity matrix
static bool s_poseValid = false;

void NRSDKBridge::updateHeadPose(JNIEnv *env, jfloatArray poseMatrix) {
  if (!poseMatrix) {
    LOGE("Null pose matrix");
    s_poseValid = false;
    return;
  }

  jsize len = env->GetArrayLength(poseMatrix);
  if (len != 16) {
    LOGE("Invalid pose matrix size: %d (expected 16)", len);
    s_poseValid = false;
    return;
  }

  // Get matrix elements
  jfloat *elements = env->GetFloatArrayElements(poseMatrix, nullptr);

  // Copy to GLM matrix (column-major)
  s_headPose = glm::make_mat4(elements);
  s_poseValid = true;

  LOGI("Head pose updated: [%.2f, %.2f, %.2f, %.2f]", s_headPose[3][0],
       s_headPose[3][1], s_headPose[3][2], s_headPose[3][3]);

  env->ReleaseFloatArrayElements(poseMatrix, elements, 0);
}

glm::mat4 NRSDKBridge::getHeadPose() {
  if (!s_poseValid) {
    LOGI("No valid head pose, returning identity");
    return glm::mat4(1.0f);
  }
  return s_headPose;
}

glm::mat4 NRSDKBridge::getViewMatrix() {
  // View matrix is inverse of pose (camera transform)
  return glm::inverse(getHeadPose());
}

bool NRSDKBridge::isPoseValid() { return s_poseValid; }

} // namespace xreal

// JNI Functions
extern "C" {

JNIEXPORT void JNICALL
Java_com_xreal_nativear_nrsdk_NRSDKWrapper_updateHeadPoseNative(
    JNIEnv *env, jobject /* this */, jfloatArray poseMatrix) {

  xreal::NRSDKBridge::updateHeadPose(env, poseMatrix);
}

} // extern "C"
