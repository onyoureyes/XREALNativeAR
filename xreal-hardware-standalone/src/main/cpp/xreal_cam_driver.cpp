#include <algorithm>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImageReader.h>
#include <vector>

#define LOG_TAG "XRealCamDriver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

class XRealCamDriver {
public:
  static void onDisconnected(void *context, ACameraDevice *device) {
    LOGI("XREAL Camera disconnected");
  }

  static void onError(void *context, ACameraDevice *device, int error) {
    LOGE("XREAL Camera error: %d", error);
  }

  // Capture Session Callbacks
  static void onSessionActive(void *context, ACameraCaptureSession *session) {
    LOGI("XREAL Session Active");
  }

  static void onSessionReady(void *context, ACameraCaptureSession *session) {
    LOGI("XREAL Session Ready");
  }
};

static ACameraDevice_StateCallbacks g_deviceCallbacks = {
    .context = nullptr,
    .onDisconnected = XRealCamDriver::onDisconnected,
    .onError = XRealCamDriver::onError};

static ACameraCaptureSession_stateCallbacks g_sessionCallbacks = {
    .context = nullptr,
    .onClosed = nullptr,
    .onReady = XRealCamDriver::onSessionReady,
    .onActive = XRealCamDriver::onSessionActive};

static ACameraDevice *g_cameraDevice = nullptr;
static ACameraCaptureSession *g_captureSession = nullptr;
static ACaptureRequest *g_captureRequest = nullptr;
static ACameraOutputTarget *g_outputTarget = nullptr;
static ACaptureSessionOutputContainer *g_container = nullptr;
static ACaptureSessionOutput *g_sessionOutput = nullptr;

extern "C" JNIEXPORT jint JNICALL
Java_com_xreal_hardware_XRealHardwareManager_nativeStartCamera(
    JNIEnv *env, jobject thiz, jobject surface) {
  LOGI("Starting XREAL Camera Driver...");

  ACameraManager *manager = ACameraManager_create();
  ACameraIdList *idList = nullptr;
  ACameraManager_getCameraIdList(manager, &idList);

  const char *cameraId = nullptr;
  for (int i = 0; i < idList->numCameras; ++i) {
    const char *id = idList->cameraIds[i];
    ACameraMetadata *metadata = nullptr;
    ACameraManager_getCameraCharacteristics(manager, id, &metadata);

    ACameraMetadata_const_entry entry;
    ACameraMetadata_getConstEntry(metadata, ACAMERA_LENS_FACING, &entry);

    if (entry.data.u8[0] == ACAMERA_LENS_FACING_EXTERNAL) {
      cameraId = id;
      LOGI("Found XREAL External Camera: %s", id);
      ACameraMetadata_free(metadata);
      break;
    }
    ACameraMetadata_free(metadata);
  }

  if (!cameraId) {
    LOGE("XREAL Camera NOT found!");
    ACameraManager_deleteCameraIdList(idList);
    ACameraManager_delete(manager);
    return -1;
  }

  ACameraManager_openCamera(manager, cameraId, &g_deviceCallbacks,
                            &g_cameraDevice);

  // Create Output for the Surface from Java
  ANativeWindow *window = ANativeWindow_fromSurface(env, surface);

  ACaptureSessionOutputContainer_create(&g_container);
  ACaptureSessionOutput_create(window, &g_sessionOutput);
  ACaptureSessionOutputContainer_add(g_container, g_sessionOutput);

  ACameraDevice_createCaptureSession(g_cameraDevice, g_container,
                                     &g_sessionCallbacks, &g_captureSession);

  ACameraDevice_createCaptureRequest(g_cameraDevice, TEMPLATE_PREVIEW,
                                     &g_captureRequest);
  ACameraOutputTarget_create(window, &g_outputTarget);
  ACaptureRequest_addTarget(g_captureRequest, g_outputTarget);

  ACameraCaptureSession_setRepeatingRequest(g_captureSession, nullptr, 1,
                                            &g_captureRequest, nullptr);

  LOGI("XREAL Camera Pipeline ACTIVE");

  ACameraManager_deleteCameraIdList(idList);
  ACameraManager_delete(manager);
  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xreal_hardware_XRealHardwareManager_nativeStopCamera(JNIEnv *env,
                                                              jobject thiz) {
  if (g_captureSession) {
    ACameraCaptureSession_stopRepeating(g_captureSession);
    ACameraCaptureSession_close(g_captureSession);
    g_captureSession = nullptr;
  }
  if (g_captureRequest) {
    ACaptureRequest_free(g_captureRequest);
    g_captureRequest = nullptr;
  }
  if (g_cameraDevice) {
    ACameraDevice_close(g_cameraDevice);
    g_cameraDevice = nullptr;
  }
  // Cleanup other resources...
}
