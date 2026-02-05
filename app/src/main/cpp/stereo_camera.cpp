#include "stereo_camera.h"
#include <android/log.h>
#include <libuvc/libuvc.h>
#include <opencv2/imgproc.hpp>

#define LOG_TAG "StereoCamera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace xreal {

StereoCamera::StereoCamera()
    : m_ctx(nullptr), m_dev(nullptr), m_devh(nullptr), m_ctrl(nullptr),
      m_initialized(false), m_streaming(false), m_lastFrame(nullptr) {}

StereoCamera::~StereoCamera() { shutdown(); }

bool StereoCamera::initialize() {
  LOGI("Initializing XREAL Light stereo camera...");

  // 1. Initialize UVC context
  uvc_error_t res = uvc_init(&m_ctx, nullptr);
  if (res < 0) {
    LOGE("Failed to initialize UVC context: %s", uvc_strerror(res));
    return false;
  }

  // 2. Find and open device
  if (!openDevice()) {
    uvc_exit(m_ctx);
    m_ctx = nullptr;
    return false;
  }

  // 3. Start streaming
  if (!startStreaming()) {
    uvc_close(m_devh);
    uvc_unref_device(m_dev);
    uvc_exit(m_ctx);
    m_ctx = nullptr;
    return false;
  }

  m_initialized = true;
  LOGI("✅ Stereo camera initialized successfully!");
  LOGI("   Resolution: %dx%d @ %d FPS", STEREO_WIDTH, STEREO_HEIGHT,
       TARGET_FPS);

  return true;
}

bool StereoCamera::openDevice() {
  LOGI("Searching for XREAL Light OV580 camera...");

  // Try to find XREAL Light by USB IDs
  uvc_error_t res = uvc_find_device(m_ctx, &m_dev, VENDOR_ID, PRODUCT_ID,
                                    nullptr // Serial number (any)
  );

  if (res < 0) {
    LOGE("Device not found (VID:0x%04x, PID:0x%04x)", VENDOR_ID, PRODUCT_ID);
    LOGI("Trying to find any UVC stereo camera...");

    // Fallback: try any device with matching resolution
    res = uvc_find_device(m_ctx, &m_dev, 0, 0, nullptr);
    if (res < 0) {
      LOGE("No UVC devices found: %s", uvc_strerror(res));
      return false;
    }
  }

  LOGI("Device found, opening...");

  // Open the device
  res = uvc_open(m_dev, &m_devh);
  if (res < 0) {
    LOGE("Failed to open device: %s", uvc_strerror(res));
    uvc_unref_device(m_dev);
    m_dev = nullptr;
    return false;
  }

  // Print device info
  uvc_device_descriptor_t *desc;
  if (uvc_get_device_descriptor(m_dev, &desc) == UVC_SUCCESS) {
    LOGI("Device opened:");
    LOGI("  Vendor:  %s", desc->manufacturer ? desc->manufacturer : "Unknown");
    LOGI("  Product: %s", desc->product ? desc->product : "Unknown");
    LOGI("  Serial:  %s", desc->serialNumber ? desc->serialNumber : "Unknown");
    uvc_free_device_descriptor(desc);
  }

  return true;
}

bool StereoCamera::startStreaming() {
  LOGI("Configuring stream: %dx%d @ %d FPS (GRAY8)", STEREO_WIDTH,
       STEREO_HEIGHT, TARGET_FPS);

  // Allocate stream control structure
  m_ctrl = (uvc_stream_ctrl *)malloc(sizeof(uvc_stream_ctrl));
  if (!m_ctrl) {
    LOGE("Failed to allocate stream control");
    return false;
  }

  // Request grayscale format at 1280x480
  uvc_error_t res =
      uvc_get_stream_ctrl_format_size(m_devh, m_ctrl,
                                      UVC_FRAME_FORMAT_GRAY8, // Grayscale
                                      STEREO_WIDTH, STEREO_HEIGHT, TARGET_FPS);

  if (res < 0) {
    LOGE("Failed to get stream control: %s", uvc_strerror(res));
    LOGI("Trying alternative formats...");

    // Try YUYV and convert to gray
    res = uvc_get_stream_ctrl_format_size(m_devh, m_ctrl, UVC_FRAME_FORMAT_YUYV,
                                          STEREO_WIDTH, STEREO_HEIGHT,
                                          TARGET_FPS);

    if (res < 0) {
      LOGE("No compatible format found");
      free(m_ctrl);
      m_ctrl = nullptr;
      return false;
    }
  }

  // Start streaming with callback
  res = uvc_start_streaming(m_devh, m_ctrl, nullptr, nullptr, 0);
  if (res < 0) {
    LOGE("Failed to start streaming: %s", uvc_strerror(res));
    free(m_ctrl);
    m_ctrl = nullptr;
    return false;
  }

  m_streaming = true;
  LOGI("✅ Streaming started!");
  return true;
}

bool StereoCamera::getFrames(cv::Mat &leftGray, cv::Mat &rightGray) {
  if (!m_streaming) {
    LOGE("Camera not streaming");
    return false;
  }

  // Get latest frame (blocking, with timeout)
  uvc_frame_t *frame;
  uvc_error_t res =
      uvc_stream_get_frame(m_devh, &frame, 1000); // 1 second timeout

  if (res < 0) {
    LOGE("Failed to get frame: %s", uvc_strerror(res));
    return false;
  }

  if (!frame || !frame->data) {
    LOGE("Invalid frame data");
    return false;
  }

  // Split side-by-side stereo frame
  splitStereoFrame(frame, leftGray, rightGray);

  return true;
}

void StereoCamera::splitStereoFrame(const uvc_frame_t *frame, cv::Mat &left,
                                    cv::Mat &right) {
  // Create OpenCV mat from UVC frame
  cv::Mat fullFrame;

  if (frame->frame_format == UVC_FRAME_FORMAT_GRAY8) {
    // Direct grayscale
    fullFrame = cv::Mat(STEREO_HEIGHT, STEREO_WIDTH, CV_8UC1, frame->data);
  } else if (frame->frame_format == UVC_FRAME_FORMAT_YUYV) {
    // Convert YUYV to grayscale
    cv::Mat yuyv(STEREO_HEIGHT, STEREO_WIDTH, CV_8UC2, frame->data);
    cv::cvtColor(yuyv, fullFrame, cv::COLOR_YUV2GRAY_YUYV);
  } else {
    LOGE("Unsupported frame format: %d", frame->frame_format);
    return;
  }

  // Split into left and right
  // Left: columns 0-639
  // Right: columns 640-1279
  left = fullFrame(cv::Rect(0, 0, SINGLE_WIDTH, SINGLE_HEIGHT)).clone();
  right =
      fullFrame(cv::Rect(SINGLE_WIDTH, 0, SINGLE_WIDTH, SINGLE_HEIGHT)).clone();
}

void StereoCamera::getCameraInfo(int &width, int &height, float &fps) const {
  width = SINGLE_WIDTH;
  height = SINGLE_HEIGHT;
  fps = TARGET_FPS;
}

void StereoCamera::shutdown() {
  LOGI("Shutting down stereo camera...");

  if (m_streaming && m_devh) {
    uvc_stop_streaming(m_devh);
    m_streaming = false;
  }

  if (m_ctrl) {
    free(m_ctrl);
    m_ctrl = nullptr;
  }

  if (m_devh) {
    uvc_close(m_devh);
    m_devh = nullptr;
  }

  if (m_dev) {
    uvc_unref_device(m_dev);
    m_dev = nullptr;
  }

  if (m_ctx) {
    uvc_exit(m_ctx);
    m_ctx = nullptr;
  }

  m_initialized = false;
  LOGI("Camera shutdown complete");
}

} // namespace xreal
