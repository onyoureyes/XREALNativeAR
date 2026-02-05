#include "qnn_runtime.h"
#include "xreal_camera.h"
#include "yolo_detector.h"
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <string>

#define LOG_TAG "XREALNativeAR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global instances
static QNNRuntime *g_qnnRuntime = nullptr;
static YOLODetector *g_yoloDetector = nullptr;
static XREALCamera *g_xrealCamera = nullptr;

extern "C" JNIEXPORT jstring JNICALL
Java_com_xreal_nativear_MainActivity_runDiagnostic(JNIEnv *env,
                                                   jobject /* this */) {
  std::string result = "Diagnostic Results:\n\n";

  // 1. Try to load libc++_shared.so
  void *handle_cpp = dlopen("libc++_shared.so", RTLD_NOW);
  if (handle_cpp) {
    result += "✅ libc++_shared.so loaded\n";
  } else {
    result += "❌ libc++_shared.so FAILED: " + std::string(dlerror()) + "\n";
  }

  // 2. Try to load libQnnCpu.so (Target)
  void *handle_qnn = dlopen("libQnnCpu.so", RTLD_NOW);
  if (handle_qnn) {
    result += "✅ libQnnCpu.so loaded\n";
  } else {
    result += "❌ libQnnCpu.so FAILED: " + std::string(dlerror()) + "\n";
  }

  // 3. Try to load libQnnHtp.so
  void *handle_htp = dlopen("libQnnHtp.so", RTLD_NOW);
  if (handle_htp) {
    result += "✅ libQnnHtp.so loaded\n";
  } else {
    result += "❌ libQnnHtp.so FAILED: " + std::string(dlerror()) + "\n";
  }

  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xreal_nativear_MainActivity_initQNN(JNIEnv *env, jobject /* this */,
                                             jstring modelPath) {

  const char *path = env->GetStringUTFChars(modelPath, nullptr);
  LOGI("Initializing QNN with model: %s", path);

  try {
    // 1. Initialize QNN Runtime
    g_qnnRuntime = new QNNRuntime();
    if (!g_qnnRuntime->initialize()) {
      LOGE("Failed to initialize QNN Runtime");
      return JNI_FALSE;
    }

    // 2. Load model
    if (!g_qnnRuntime->loadModel(path)) {
      LOGE("Failed to load model");
      return JNI_FALSE;
    }

    // 3. Initialize YOLO detector
    g_yoloDetector = new YOLODetector(g_qnnRuntime);

    // 4. Initialize XREAL Camera
    g_xrealCamera = new XREALCamera();
    if (!g_xrealCamera->initialize()) {
      LOGE("Failed to initialize XREAL Camera (Glasses disconnected?)");
      // We continue even if camera fails, for diagnostics
    }

    LOGI("Native Pipeline initialized successfully");
    env->ReleaseStringUTFChars(modelPath, path);
    return JNI_TRUE;

  } catch (const std::exception &e) {
    LOGE("Exception during Native init: %s", e.what());
    env->ReleaseStringUTFChars(modelPath, path);
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_xreal_nativear_MainActivity_detectObjects(JNIEnv *env,
                                                   jobject /* this */,
                                                   jbyteArray imageData,
                                                   jint width, jint height) {

  // Fallback: If no data passed from Java, try to get frame from native camera
  std::vector<uint8_t> frameData;
  uint8_t *pixels = nullptr;
  int finalWidth = width;
  int finalHeight = height;

  if (imageData) {
    pixels = reinterpret_cast<uint8_t *>(
        env->GetByteArrayElements(imageData, nullptr));
  } else if (g_xrealCamera) {
    // Pure Native Path
    frameData.resize(640 * 480 * 3);
    if (g_xrealCamera->getFrame(frameData.data(), frameData.size())) {
      pixels = frameData.data();
      finalWidth = 640;
      finalHeight = 480;
    }
  }

  if (!pixels || !g_yoloDetector) {
    if (imageData)
      env->ReleaseByteArrayElements(imageData, (jbyte *)pixels, JNI_ABORT);
    return nullptr;
  }

  // Run detection
  auto detections = g_yoloDetector->detect(pixels, finalWidth, finalHeight);

  if (imageData)
    env->ReleaseByteArrayElements(imageData, (jbyte *)pixels, JNI_ABORT);

  // Convert C++ vector to Java array
  jclass detectionClass = env->FindClass("com/xreal/nativear/Detection");
  jmethodID constructor =
      env->GetMethodID(detectionClass, "<init>", "(Ljava/lang/String;FFFFF)V");

  jobjectArray result =
      env->NewObjectArray(detections.size(), detectionClass, nullptr);

  for (size_t i = 0; i < detections.size(); i++) {
    const auto &det = detections[i];
    jstring label = env->NewStringUTF(det.label.c_str());
    jobject detection =
        env->NewObject(detectionClass, constructor, label, det.confidence,
                       det.x, det.y, det.width, det.height);
    env->SetObjectArrayElement(result, i, detection);
    env->DeleteLocalRef(label);
    env->DeleteLocalRef(detection);
  }

  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xreal_nativear_MainActivity_cleanup(JNIEnv *env, jobject /* this */) {

  LOGI("Cleaning up native resources");

  delete g_xrealCamera;
  g_xrealCamera = nullptr;

  delete g_yoloDetector;
  g_yoloDetector = nullptr;

  delete g_qnnRuntime;
  g_qnnRuntime = nullptr;
}
