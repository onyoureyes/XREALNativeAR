#ifndef XREAL_NRSDK_BRIDGE_H
#define XREAL_NRSDK_BRIDGE_H

#include <glm/glm.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>
#include <jni.h>

namespace xreal {

/**
 * NRSDKBridge - Handles NRSDK Java → Native communication
 *
 * Receives 6DoF pose from NRSDK Java wrapper and provides it to native
 * rendering
 */
class NRSDKBridge {
public:
  /**
   * Update head pose from Java (called via JNI)
   * @param env JNI environment
   * @param poseMatrix 4x4 transformation matrix (16 floats)
   */
  static void updateHeadPose(JNIEnv *env, jfloatArray poseMatrix);

  /**
   * Get current head pose (world → head transform)
   */
  static glm::mat4 getHeadPose();

  /**
   * Get view matrix for rendering (head → world transform)
   */
  static glm::mat4 getViewMatrix();

  /**
   * Check if pose data is valid
   */
  static bool isPoseValid();
};

} // namespace xreal

#endif // XREAL_NRSDK_BRIDGE_H
