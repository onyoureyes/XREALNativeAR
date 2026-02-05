#ifndef XREAL_STEREO_CAMERA_H
#define XREAL_STEREO_CAMERA_H

#include <opencv2/core.hpp>
#include <string>

// Forward declarations for libuvc
struct uvc_context;
struct uvc_device;
struct uvc_device_handle;
struct uvc_stream_ctrl;
struct uvc_frame;

namespace xreal {

/**
 * StereoCamera - Access XREAL Light's grayscale stereo cameras via UVC
 *
 * The OV580 chip on XREAL Light outputs both cameras side-by-side
 * as a single 1280x480 grayscale image at 30 FPS.
 */
class StereoCamera {
public:
  StereoCamera();
  ~StereoCamera();

  /**
   * Initialize UVC connection to XREAL Light stereo cameras
   * @return true if successful
   */
  bool initialize();

  /**
   * Get latest stereo frame pair
   * @param leftGray Output: left camera image (640x480)
   * @param rightGray Output: right camera image (640x480)
   * @return true if frames acquired successfully
   */
  bool getFrames(cv::Mat &leftGray, cv::Mat &rightGray);

  /**
   * Get camera information
   */
  void getCameraInfo(int &width, int &height, float &fps) const;

  /**
   * Check if camera is initialized and streaming
   */
  bool isStreaming() const { return m_streaming; }

  /**
   * Stop streaming and cleanup
   */
  void shutdown();

private:
  // UVC handles
  uvc_context *m_ctx;
  uvc_device *m_dev;
  uvc_device_handle *m_devh;
  uvc_stream_ctrl *m_ctrl;

  // Camera parameters
  static constexpr int STEREO_WIDTH = 1280; // Side-by-side
  static constexpr int STEREO_HEIGHT = 480;
  static constexpr int SINGLE_WIDTH = 640;
  static constexpr int SINGLE_HEIGHT = 480;
  static constexpr int TARGET_FPS = 30;

  // XREAL Light OV580 USB IDs (verified from community)
  static constexpr int VENDOR_ID = 0x0bda;  // Realtek (OV580 bridge)
  static constexpr int PRODUCT_ID = 0x0580; // OV580 Camera
  // Alternative IDs to try: 0x5740, 0x58f0

  // IMPORTANT: These are FISHEYE cameras, not pinhole!
  // Requires distortion correction before SLAM

  // State
  bool m_initialized;
  bool m_streaming;
  uvc_frame *m_lastFrame;

  // Internal helpers
  bool openDevice();
  bool startStreaming();
  void splitStereoFrame(const uvc_frame *frame, cv::Mat &left, cv::Mat &right);
};

} // namespace xreal

#endif // XREAL_STEREO_CAMERA_H
