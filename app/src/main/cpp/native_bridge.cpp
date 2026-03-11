#include <android/log.h>
#include <jni.h>
#include <string>

#define LOG_TAG "XREALNativeAR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// llama_bridge.cpp에서 JNI 함수 제공 (LlamaCppBridge.kt 바인딩)
// 이 파일은 추후 네이티브 JNI 유틸리티 함수 추가 시 사용
