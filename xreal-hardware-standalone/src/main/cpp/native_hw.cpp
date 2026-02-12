#include <android/log.h>
#include <atomic>
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define LOG_TAG "XRealHWNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = nullptr;
static jobject g_managerObj = nullptr;
static jmethodID g_onImuMethod = nullptr;
static std::atomic<bool> g_imuRunning(false);
static pthread_t g_imuThread;
static int g_usbFd = -1;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  g_vm = vm;
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xreal_hardware_XRealHardwareManager_nativeActivate(JNIEnv *env,
                                                            jobject /* this */,
                                                            jint fd) {
  LOGI("Attempting Bare Metal activation of XREAL (fd: %d)", fd);

  // Magic Packet for Nreal Light activation (HID Report ID 3)
  // Payload: [ReportID, Data...]
  uint8_t payload[] = {0x03, 0x02, 0x01, 0x02};

  struct usbdevfs_ctrltransfer ctrl;
  ctrl.bRequestType = 0x21; // Host to Device | Class | Interface
  ctrl.bRequest = 0x09;     // SET_REPORT
  ctrl.wValue = 0x0303;     // Report Type: Feature (0x03) | Report ID (0x03)
  ctrl.wIndex = 0x0002;     // Interface 2 (HID typical for OV580)
  ctrl.wLength = sizeof(payload);
  ctrl.data = payload;
  ctrl.timeout = 1000;

  int ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
  if (ret < 0) {
    LOGE("❌ Interface 2 Activation Failed (ioctl: %d). Trying Interface 3...",
         ret);
    ctrl.wIndex = 0x0003;
    ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
  }

  if (ret >= 0) {
    LOGI("✅ Magic packet sent successfully!");
    return 0;
  } else {
    LOGE("❌ Activation FAILED on all interfaces (ioctl: %d)", ret);
    return ret;
  }
}

#define ENDPOINT_IN 0x81
#define ENDPOINT_OUT 0x01
#define HID_INTERFACE 3

void *imu_thread_func(void *arg) {
  JNIEnv *env = nullptr;
  if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
    LOGE("Failed to attach IMU thread to JVM");
    return nullptr;
  }

  LOGI("Real IMU Thread Started");

  // 1. Claim Interface
  int iface = HID_INTERFACE;
  if (ioctl(g_usbFd, USBDEVFS_CLAIMINTERFACE, &iface) < 0) {
    LOGE("❌ Failed to claim HID interface: %d", errno);
    g_vm->DetachCurrentThread();
    return nullptr;
  }

  // 2. Handshake (Magic Packet)
  uint8_t handshake[] = {0x02, 0x19, 0x01};
  struct usbdevfs_bulktransfer bulkOut;
  bulkOut.ep = ENDPOINT_OUT;
  bulkOut.len = sizeof(handshake);
  bulkOut.timeout = 1000;
  bulkOut.data = handshake;

  if (ioctl(g_usbFd, USBDEVFS_BULK, &bulkOut) < 0) {
    LOGE("❌ Handshake failed: %d", errno);
  } else {
    LOGI("✅ Handshake sent successfully");
  }

  uint8_t buffer[64];
  struct usbdevfs_bulktransfer bulkIn;
  bulkIn.ep = ENDPOINT_IN;
  bulkIn.len = sizeof(buffer);
  bulkIn.timeout = 100;
  bulkIn.data = buffer;

  // Simple Sensor Fusion State
  float qx = 0, qy = 0, qz = 0, qw = 1.0f;

  while (g_imuRunning) {
    int ret = ioctl(g_usbFd, USBDEVFS_BULK, &bulkIn);
    if (ret >= 64) {
      if (buffer[0] == 0x01) { // IMU Data
        // Parse Gyro (Offset 0x3C, 3*s16)
        int16_t gx_raw = (int16_t)(buffer[0x3C] | (buffer[0x3D] << 8));
        int16_t gy_raw = (int16_t)(buffer[0x3E] | (buffer[0x3F] << 8));
        int16_t gz_raw = (int16_t)(buffer[0x40] | (buffer[0x41] << 8));

        float gx = gx_raw / 16.4f * (M_PI / 180.0f); // rad/s
        float gy = gy_raw / 16.4f * (M_PI / 180.0f);
        float gz = gz_raw / 16.4f * (M_PI / 180.0f);

        // Parse Accel (Offset 0x58, 3*s16)
        int16_t ax_raw = (int16_t)(buffer[0x58] | (buffer[0x59] << 8));
        int16_t ay_raw = (int16_t)(buffer[0x5A] | (buffer[0x5B] << 8));
        int16_t az_raw = (int16_t)(buffer[0x5C] | (buffer[0x5D] << 8));

        float ax = ax_raw / 16384.0f;
        float ay = ay_raw / 16384.0f;
        float az = az_raw / 16384.0f;

        // Proper 3DoF Quaternion Integration (Gyro based)
        static uint64_t last_time = 0;
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        uint64_t current_time = ts.tv_sec * 1000000000ULL + ts.tv_nsec;

        if (last_time != 0) {
          float dt = (current_time - last_time) / 1e9f;

          // Quaternion update from angular velocity: q_dot = 0.5 * q * omega
          float dqx = 0.5f * (qw * gx + qy * gz - qz * gy);
          float dqy = 0.5f * (qw * gy - qx * gz + qz * gx);
          float dqz = 0.5f * (qw * gz + qx * gy - qy * gx);
          float dqw = 0.5f * (-qx * gx - qy * gy - qz * gz);

          qx += dqx * dt;
          qy += dqy * dt;
          qz += dqz * dt;
          qw += dqw * dt;

          // Normalize
          float norm = sqrtf(qx * qx + qy * qy + qz * qz + qw * qw);
          qx /= norm;
          qy /= norm;
          qz /= norm;
          qw /= norm;
        }
        last_time = current_time;

        if (g_managerObj && g_onImuMethod) {
          env->CallVoidMethod(g_managerObj, g_onImuMethod, qx, qy, qz, qw);
        }
      }
    }
  }

  ioctl(g_usbFd, USBDEVFS_RELEASEINTERFACE, &iface);
  g_vm->DetachCurrentThread();
  LOGI("IMU Thread Stopped");
  return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xreal_hardware_XRealHardwareManager_nativeStartIMU(JNIEnv *env,
                                                            jobject thiz,
                                                            jint fd) {
  if (g_imuRunning)
    return 0;

  g_usbFd = fd;
  g_managerObj = env->NewGlobalRef(thiz);
  jclass clazz = env->GetObjectClass(thiz);
  g_onImuMethod = env->GetMethodID(clazz, "onNativeIMU", "(FFFF)V");

  g_imuRunning = true;
  pthread_create(&g_imuThread, nullptr, imu_thread_func, nullptr);

  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xreal_hardware_XRealHardwareManager_nativeStopIMU(JNIEnv *env,
                                                           jobject thiz) {
  g_imuRunning = false;
  pthread_join(g_imuThread, nullptr);

  if (g_managerObj) {
    env->DeleteGlobalRef(g_managerObj);
    g_managerObj = nullptr;
  }
}
