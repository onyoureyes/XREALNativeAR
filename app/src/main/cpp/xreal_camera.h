#ifndef XREAL_CAMERA_H
#define XREAL_CAMERA_H

#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraError.h>
#include <camera/NdkCameraManager.h>
#include <cstdint>
#include <media/NdkImageReader.h>

/**
 * XREAL Light camera interface
 * Uses Android Camera2 API to access XREAL camera via USB
 */
class XREALCamera {
public:
  XREALCamera();
  ~XREALCamera();

  /**
   * Initialize camera (XREAL USB device)
   * @return true if successful
   */
  bool initialize();

  /**
   * Get latest camera frame
   * @param outBuffer Buffer to receive RGB data (pre-allocated)
   * @param bufferSize Buffer size in bytes
   * @return true if frame acquired
   */
  [[maybe_unused]] bool getFrame(uint8_t *outBuffer, size_t bufferSize);

  /**
   * Get camera resolution
   */
  [[maybe_unused]] void getResolution(int &width, int &height) const;

  // Callback helpers (must be public for global struct access)
  static void onDisconnected(void *context, ACameraDevice *device);
  static void onError(void *context, ACameraDevice *device, int error);

private:
  int m_width;
  int m_height;

  ACameraDevice *m_cameraDevice;
  ACameraCaptureSession *m_captureSession;
  AImageReader *m_imageReader;
  ANativeWindow *m_readerWindow;
  ACaptureRequest *m_captureRequest;
  ACameraOutputTarget *m_cameraOutputTarget;
  ACaptureSessionOutput *m_sessionOutput;
  ACaptureSessionOutputContainer *m_outputContainer;
};

#endif // XREAL_CAMERA_H
