#include "yolo_detector.h"
#include <algorithm>
#include <android/log.h>
#include <cmath>


#define LOG_TAG "YOLODetector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// COCO class labels (80 classes)
static const char *COCO_CLASSES[] = {
    "person",        "bicycle",      "car",
    "motorcycle",    "airplane",     "bus",
    "train",         "truck",        "boat",
    "traffic light", "fire hydrant", "stop sign",
    "parking meter", "bench",        "bird",
    "cat",           "dog",          "horse",
    "sheep",         "cow",          "elephant",
    "bear",          "zebra",        "giraffe",
    "backpack",      "umbrella",     "handbag",
    "tie",           "suitcase",     "frisbee",
    "skis",          "snowboard",    "sports ball",
    "kite",          "baseball bat", "baseball glove",
    "skateboard",    "surfboard",    "tennis racket",
    "bottle",        "wine glass",   "cup",
    "fork",          "knife",        "spoon",
    "bowl",          "banana",       "apple",
    "sandwich",      "orange",       "broccoli",
    "carrot",        "hot dog",      "pizza",
    "donut",         "cake",         "chair",
    "couch",         "potted plant", "bed",
    "dining table",  "toilet",       "tv",
    "laptop",        "mouse",        "remote",
    "keyboard",      "cell phone",   "microwave",
    "oven",          "toaster",      "sink",
    "refrigerator",  "book",         "clock",
    "vase",          "scissors",     "teddy bear",
    "hair drier",    "toothbrush"};

YOLODetector::YOLODetector(QNNRuntime *runtime)
    : m_runtime(runtime), m_confidenceThreshold(0.5f), m_iouThreshold(0.45f) {}

YOLODetector::~YOLODetector() {
  // Runtime is owned externally, don't delete
}

std::vector<Detection> YOLODetector::detect(const uint8_t *imageData, int width,
                                            int height) {
  // Get model input dimensions
  int inputW, inputH, inputC;
  m_runtime->getInputDims(inputW, inputH, inputC);

  // Preprocess image (resize to 300x300)
  size_t inputSize = inputW * inputH * inputC;
  auto *preprocessed = new uint8_t[inputSize];
  preprocessImage(imageData, width, height, preprocessed, inputW, inputH);

  // Run NPU inference
  int numDet, numClasses;
  m_runtime->getOutputDims(numDet, numClasses);
  size_t outputSize = numDet * numClasses;
  auto *output = new float[outputSize];

  if (!m_runtime->execute(preprocessed, inputSize, output,
                          outputSize * sizeof(float))) {
    LOGI("NPU inference failed");
    delete[] preprocessed;
    delete[] output;
    return {};
  }

  // Parse YOLO output
  auto detections = parseOutput(output, numDet, numClasses);

  // Apply NMS
  detections = nms(detections);

  delete[] preprocessed;
  delete[] output;

  LOGI("Detected %zu objects", detections.size());
  return detections;
}

void YOLODetector::preprocessImage(const uint8_t *src, int srcWidth,
                                   int srcHeight, uint8_t *dst, int dstWidth,
                                   int dstHeight) {
  // Simple nearest-neighbor resize
  // In production, use bilinear interpolation

  float xRatio = static_cast<float>(srcWidth) / dstWidth;
  float yRatio = static_cast<float>(srcHeight) / dstHeight;

  for (int y = 0; y < dstHeight; y++) {
    for (int x = 0; x < dstWidth; x++) {
      int srcX = static_cast<int>(x * xRatio);
      int srcY = static_cast<int>(y * yRatio);

      // RGB channels
      for (int c = 0; c < 3; c++) {
        int srcIdx = (srcY * srcWidth + srcX) * 3 + c;
        int dstIdx = (y * dstWidth + x) * 3 + c;
        dst[dstIdx] = src[srcIdx];
      }
    }
  }
}

std::vector<Detection> YOLODetector::parseOutput(const float *outputTensor,
                                                 int numDetections,
                                                 int numClasses) {
  std::vector<Detection> detections;

  // YOLOv5 output format: [num_detections, 85]
  // 85 = x, y, w, h, conf, 80 class scores

  for (int i = 0; i < numDetections; i++) {
    const float *row = &outputTensor[i * numClasses];

    float confidence = row[4];
    if (confidence < m_confidenceThreshold) {
      continue;
    }

    // Find max class score
    int maxClassId = 0;
    float maxClassScore = row[5];
    for (int c = 1; c < 80; c++) {
      if (row[5 + c] > maxClassScore) {
        maxClassScore = row[5 + c];
        maxClassId = c;
      }
    }

    float finalConf = confidence * maxClassScore;
    if (finalConf < m_confidenceThreshold) {
      continue;
    }

    // Bounding box (normalized 0-1)
    Detection det;
    det.label = COCO_CLASSES[maxClassId];
    det.confidence = finalConf;
    det.x = row[0];
    det.y = row[1];
    det.width = row[2];
    det.height = row[3];

    detections.push_back(det);
  }

  return detections;
}

std::vector<Detection> YOLODetector::nms(std::vector<Detection> &detections) {
  // Sort by confidence (descending)
  std::sort(detections.begin(), detections.end(),
            [](const Detection &a, const Detection &b) {
              return a.confidence > b.confidence;
            });

  std::vector<Detection> result;

  while (!detections.empty()) {
    // Take best detection
    Detection best = detections.front();
    result.push_back(best);
    detections.erase(detections.begin());

    // Remove overlapping boxes
    detections.erase(std::remove_if(detections.begin(), detections.end(),
                                    [&](const Detection &d) {
                                      return calculateIoU(best, d) >
                                             m_iouThreshold;
                                    }),
                     detections.end());
  }

  return result;
}

float YOLODetector::calculateIoU(const Detection &a, const Detection &b) {
  float x1 = std::max(a.x, b.x);
  float y1 = std::max(a.y, b.y);
  float x2 = std::min(a.x + a.width, b.x + b.width);
  float y2 = std::min(a.y + a.height, b.y + b.height);

  float intersection = std::max(0.0f, x2 - x1) * std::max(0.0f, y2 - y1);
  float unionArea = a.width * a.height + b.width * b.height - intersection;

  return intersection / unionArea;
}
