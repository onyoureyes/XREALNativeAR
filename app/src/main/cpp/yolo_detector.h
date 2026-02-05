#ifndef YOLO_DETECTOR_H
#define YOLO_DETECTOR_H

#include "qnn_runtime.h"
#include <string>
#include <vector>


struct Detection {
  std::string label;
  float confidence;
  float x, y, width, height;
};

/**
 * YOLO object detector using QNN NPU
 */
class YOLODetector {
public:
  explicit YOLODetector(QNNRuntime *runtime);
  ~YOLODetector();

  /**
   * Detect objects in image
   * @param imageData RGB image data
   * @param width Image width
   * @param height Image height
   * @return Vector of detections
   */
  std::vector<Detection> detect(const uint8_t *imageData, int width,
                                int height);

private:
  QNNRuntime *m_runtime;

  float m_confidenceThreshold;
  float m_iouThreshold;

  /**
   * Preprocess image for YOLO input
   */
  void preprocessImage(const uint8_t *src, int srcWidth, int srcHeight,
                       uint8_t *dst, int dstWidth, int dstHeight);

  /**
   * Parse YOLO output tensor
   */
  std::vector<Detection> parseOutput(const float *outputTensor,
                                     int numDetections, int numClasses);

  /**
   * Non-Maximum Suppression
   */
  std::vector<Detection> nms(std::vector<Detection> &detections);

  /**
   * Calculate IoU (Intersection over Union)
   */
  float calculateIoU(const Detection &a, const Detection &b);
};

#endif // YOLO_DETECTOR_H
