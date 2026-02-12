#include "xreal_camera.h"
#include <algorithm>
#include <android/log.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImage.h>
#include <string>
#include <vector>

#define LOG_TAG "XREALCamera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Callback wrappers
void XREALCamera::onDisconnected(void *context,
                                 [[maybe_unused]] ACameraDevice *device) {
  LOGI("Camera disconnected");
}

void XREALCamera::onError(void *context, [[maybe_unused]] ACameraDevice *device,
                          int error) {
  LOGE("Camera error: %d", error);
}

// Global static callback struct
static ACameraDevice_StateCallbacks g_deviceStateCallbacks = {
    .context = nullptr,
    .onDisconnected = XREALCamera::onDisconnected,
    .onError = XREALCamera::onError};

XREALCamera::XREALCamera()
    : m_width(640), m_height(480), m_cameraDevice(nullptr),
      m_captureSession(nullptr), m_imageReader(nullptr),
      m_readerWindow(nullptr), m_captureRequest(nullptr),
      m_cameraOutputTarget(nullptr), m_sessionOutput(nullptr),
      m_outputContainer(nullptr) {}

XREALCamera::~XREALCamera() {
  if (m_captureSession) {
    ACameraCaptureSession_stopRepeating(m_captureSession);
    ACameraCaptureSession_close(m_captureSession);
  }
  if (m_captureRequest) {
    ACaptureRequest_free(m_captureRequest);
  }
  if (m_cameraOutputTarget) {
    ACameraOutputTarget_free(m_cameraOutputTarget);
  }
  if (m_outputContainer) {
    ACaptureSessionOutputContainer_free(m_outputContainer);
  }
  if (m_sessionOutput) {
    ACaptureSessionOutput_free(m_sessionOutput);
  }
  if (m_cameraDevice) {
    ACameraDevice_close(m_cameraDevice);
  }
  if (m_imageReader) {
    AImageReader_delete(m_imageReader);
  }
}

// Session state callbacks
void onSessionActive(void *context, ACameraCaptureSession *session) {
  LOGI("Camera session active");
}
void onSessionReady(void *context, ACameraCaptureSession *session) {
  LOGI("Camera session ready");
}
void onSessionClosed(void *context, ACameraCaptureSession *session) {
  LOGI("Camera session closed");
}

static ACameraCaptureSession_stateCallbacks g_sessionStateCallbacks = {
    .context = nullptr,
    .onClosed = onSessionClosed,
    .onReady = onSessionReady,
    .onActive = onSessionActive};

bool XREALCamera::initialize() {
  LOGI("Initializing XREAL camera (640x480) via NDK");

  ACameraManager *cameraManager = ACameraManager_create();
  ACameraIdList *cameraIds = nullptr;
  ACameraManager_getCameraIdList(cameraManager, &cameraIds);

  const char *selectedCameraId = nullptr;

  // Look for EXTERNAL camera (XREAL Glasses)
  for (int i = 0; i < cameraIds->numCameras; ++i) {
    const char *id = cameraIds->cameraIds[i];
    ACameraMetadata *metadata = nullptr;
    ACameraManager_getCameraCharacteristics(cameraManager, id, &metadata);

    acamera_metadata_tag_t facingTag = ACAMERA_LENS_FACING;
    ACameraMetadata_const_entry entry;
    ACameraMetadata_getConstEntry(metadata, facingTag, &entry);

    uint8_t facing = entry.data.u8[0];
    LOGI("Camera ID %s facing: %d (0:Front, 1:Back, 2:External)", id, facing);

    if (facing == ACAMERA_LENS_FACING_EXTERNAL) {
      selectedCameraId = id;
      LOGI("Found External Camera (XREAL): ID %s", id);
    }
    ACameraMetadata_free(metadata);
  }

  // Fallback if no external camera found
  if (!selectedCameraId && cameraIds->numCameras > 0) {
    selectedCameraId = cameraIds->cameraIds[0];
    LOGI("No External camera found, falling back to ID %s", selectedCameraId);
  }

  if (!selectedCameraId) {
    LOGE("No camera found at all!");
    ACameraManager_deleteCameraIdList(cameraIds);
    ACameraManager_delete(cameraManager);
    return false;
  }

  // Open Camera
  ACameraManager_openCamera(cameraManager, selectedCameraId,
                            &g_deviceStateCallbacks, &m_cameraDevice);
  ACameraManager_deleteCameraIdList(cameraIds);
  ACameraManager_delete(cameraManager);

  // Create ImageReader
  AImageReader_new(m_width, m_height, AIMAGE_FORMAT_YUV_420_888, 2,
                   &m_imageReader);
  AImageReader_getWindow(m_imageReader, &m_readerWindow);

  // Create Capture Session
  ACaptureSessionOutputContainer_create(&m_outputContainer);
  ACaptureSessionOutput_create(m_readerWindow, &m_sessionOutput);
  ACaptureSessionOutputContainer_add(m_outputContainer, m_sessionOutput);

  ACameraDevice_createCaptureSession(m_cameraDevice, m_outputContainer,
                                     &g_sessionStateCallbacks,
                                     &m_captureSession);

  // Create Capture Request
  ACameraDevice_createCaptureRequest(m_cameraDevice, TEMPLATE_PREVIEW,
                                     &m_captureRequest);
  ACameraOutputTarget_create(m_readerWindow, &m_cameraOutputTarget);
  ACaptureRequest_addTarget(m_captureRequest, m_cameraOutputTarget);

  // Start Repeating Request
  ACameraCaptureSession_setRepeatingRequest(m_captureSession, nullptr, 1,
                                            &m_captureRequest, nullptr);

  LOGI("XREAL Camera Hardware Pipeline Started");
  return true;
}

[[maybe_unused]] bool XREALCamera::getFrame(uint8_t *outBuffer,
                                            size_t bufferSize) {
  if (!m_imageReader)
    return false;

  AImage *image = nullptr;
  media_status_t status =
      AImageReader_acquireLatestImage(m_imageReader, &image);

  if (status != AMEDIA_OK || !image) {
    return false;
  }

  // Get image format
  int32_t format = 0;
  AImage_getFormat(image, &format);

  if (format != AIMAGE_FORMAT_YUV_420_888) {
    LOGE("Unexpected image format: %d", format);
    AImage_delete(image);
    return false;
  }

  // Get image dimensions
  int32_t width = 0, height = 0;
  AImage_getWidth(image, &width);
  AImage_getHeight(image, &height);

  // Check buffer size
  size_t expectedSize = static_cast<size_t>(width) * height * 3; // RGB
  if (bufferSize < expectedSize) {
    LOGE("Buffer too small: need %zu, have %zu", expectedSize, bufferSize);
    AImage_delete(image);
    return false;
  }

  // Get YUV planes
  uint8_t *yPlane = nullptr, *uPlane = nullptr, *vPlane = nullptr;
  int32_t yLen = 0, uLen = 0, vLen = 0;
  int32_t yStride = 0, uvStride = 0;
  int32_t uvPixelStride = 0;

  AImage_getPlaneData(image, 0, &yPlane, &yLen);
  AImage_getPlaneData(image, 1, &uPlane, &uLen);
  AImage_getPlaneData(image, 2, &vPlane, &vLen);

  AImage_getPlaneRowStride(image, 0, &yStride);
  AImage_getPlaneRowStride(image, 1, &uvStride);
  AImage_getPlanePixelStride(image, 1, &uvPixelStride);

  // Convert YUV to RGB
  for (int y = 0; y < height; y++) {
    for (int x = 0; x < width; x++) {
      // Get Y value
      int yIndex = y * yStride + x;
      auto Y_f = static_cast<float>(yPlane[yIndex]);

      // Get U, V values (subsampled 2x2)
      int uvRow = y / 2;
      int uvCol = x / 2;
      int uvIndex = uvRow * uvStride + uvCol * uvPixelStride;
      auto U_f = static_cast<float>(uPlane[uvIndex]) - 128.0f;
      auto V_f = static_cast<float>(vPlane[uvIndex]) - 128.0f;

      // YUV to RGB conversion (BT.601)
      int R = static_cast<int>(Y_f + (1.370705f * V_f));
      int G = static_cast<int>(Y_f - (0.337633f * U_f) - (0.698001f * V_f));
      int B = static_cast<int>(Y_f + (1.732446f * U_f));

      // Clamp to [0, 255]
      R = std::max(0, std::min(255, R));
      G = std::max(0, std::min(255, G));
      B = std::max(0, std::min(255, B));

      // Write RGB to output buffer
      int outIndex = (y * width + x) * 3;
      outBuffer[outIndex + 0] = static_cast<uint8_t>(R);
      outBuffer[outIndex + 1] = static_cast<uint8_t>(G);
      outBuffer[outIndex + 2] = static_cast<uint8_t>(B);
    }
  }

  AImage_delete(image);
  return true;
}

[[maybe_unused]] void XREALCamera::getResolution(int &width,
                                                 int &height) const {
  width = m_width;
  height = m_height;
}
