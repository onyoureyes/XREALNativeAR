#include <android/log.h>
#include <atomic>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/videodev2.h>
#include <pthread.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#define LOG_TAG "V4L2RGB"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr int NUM_BUFFERS = 4;

struct V4L2Buffer {
    void *start;
    size_t length;
};

static int g_v4l2Fd = -1;
static V4L2Buffer g_buffers[NUM_BUFFERS];
static int g_numBuffers = 0;
static std::atomic<bool> g_streaming(false);
static pthread_t g_captureThread;
static JavaVM *g_jvm = nullptr;
static jobject g_callbackObj = nullptr;
static jmethodID g_onFrameMethod = nullptr;

// Find RGB camera V4L2 device by scanning /dev/video*
static int findRGBCameraDevice() {
    char path[64];
    struct v4l2_capability cap;

    for (int i = 0; i < 20; i++) {
        snprintf(path, sizeof(path), "/dev/video%d", i);
        int fd = open(path, O_RDWR | O_NONBLOCK);
        if (fd < 0) {
            if (errno == EACCES) {
                LOGI("  %s: Permission denied (EACCES)", path);
            }
            continue;
        }

        if (ioctl(fd, VIDIOC_QUERYCAP, &cap) == 0) {
            LOGI("  %s: driver=%s card=%s bus=%s caps=0x%08x",
                 path, cap.driver, cap.card, cap.bus_info, cap.capabilities);

            // Check if it's a UVC device with "nreal" or "USB Camera" in the name
            bool isUvc = (strstr((const char *)cap.driver, "uvcvideo") != nullptr);
            bool isNreal = (strstr((const char *)cap.card, "nreal") != nullptr ||
                           strstr((const char *)cap.card, "USB Camera") != nullptr);
            bool hasCapture = (cap.capabilities & V4L2_CAP_VIDEO_CAPTURE) != 0;
            bool hasStreaming = (cap.capabilities & V4L2_CAP_STREAMING) != 0;

            LOGI("    isUvc=%d isNreal=%d hasCapture=%d hasStreaming=%d",
                 isUvc, isNreal, hasCapture, hasStreaming);

            if (isNreal && hasCapture) {
                LOGI("  >>> FOUND RGB Camera: %s <<<", path);
                // Also enumerate formats
                struct v4l2_fmtdesc fmtdesc;
                memset(&fmtdesc, 0, sizeof(fmtdesc));
                fmtdesc.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
                while (ioctl(fd, VIDIOC_ENUM_FMT, &fmtdesc) == 0) {
                    LOGI("    Format: %s (0x%08x)", fmtdesc.description, fmtdesc.pixelformat);
                    fmtdesc.index++;
                }
                return fd; // Return open fd
            }
        }
        close(fd);
    }
    return -1;
}

static int setupFormat(int fd) {
    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;

    // First, get current format
    if (ioctl(fd, VIDIOC_G_FMT, &fmt) == 0) {
        LOGI("Current format: %dx%d pixfmt=0x%08x",
             fmt.fmt.pix.width, fmt.fmt.pix.height, fmt.fmt.pix.pixelformat);
    }

    // Try MJPEG 640x480 first (common UVC format)
    fmt.fmt.pix.width = 640;
    fmt.fmt.pix.height = 480;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_MJPEG;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;

    if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        LOGI("MJPEG not supported, trying YUYV...");
        fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUYV;
        if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
            LOGE("Failed to set format: %s", strerror(errno));
            return -1;
        }
    }

    LOGI("Set format: %dx%d pixfmt=0x%08x bytesperline=%d sizeimage=%d",
         fmt.fmt.pix.width, fmt.fmt.pix.height, fmt.fmt.pix.pixelformat,
         fmt.fmt.pix.bytesperline, fmt.fmt.pix.sizeimage);
    return 0;
}

static int setupMmap(int fd) {
    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = NUM_BUFFERS;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;

    if (ioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        LOGE("VIDIOC_REQBUFS failed: %s", strerror(errno));
        return -1;
    }

    g_numBuffers = req.count;
    LOGI("Allocated %d buffers", g_numBuffers);

    for (int i = 0; i < g_numBuffers; i++) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;

        if (ioctl(fd, VIDIOC_QUERYBUF, &buf) < 0) {
            LOGE("VIDIOC_QUERYBUF failed for buffer %d: %s", i, strerror(errno));
            return -1;
        }

        g_buffers[i].length = buf.length;
        g_buffers[i].start = mmap(nullptr, buf.length,
                                   PROT_READ | PROT_WRITE, MAP_SHARED,
                                   fd, buf.m.offset);
        if (g_buffers[i].start == MAP_FAILED) {
            LOGE("mmap failed for buffer %d: %s", i, strerror(errno));
            return -1;
        }

        LOGI("Buffer %d: length=%zu", i, g_buffers[i].length);
    }

    // Queue all buffers
    for (int i = 0; i < g_numBuffers; i++) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;

        if (ioctl(fd, VIDIOC_QBUF, &buf) < 0) {
            LOGE("VIDIOC_QBUF failed for buffer %d: %s", i, strerror(errno));
            return -1;
        }
    }

    return 0;
}

static void *captureThreadFunc(void *arg) {
    JNIEnv *env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach capture thread");
        return nullptr;
    }

    LOGI("V4L2 capture thread started");

    int frameCount = 0;
    struct timespec startTime;
    clock_gettime(CLOCK_MONOTONIC, &startTime);

    while (g_streaming) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;

        // Use select() for timeout instead of blocking ioctl
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(g_v4l2Fd, &fds);
        struct timeval tv;
        tv.tv_sec = 2;
        tv.tv_usec = 0;

        int r = select(g_v4l2Fd + 1, &fds, nullptr, nullptr, &tv);
        if (r <= 0) {
            if (r == 0) {
                LOGI("V4L2 select timeout (no frame in 2s)");
            } else {
                LOGE("V4L2 select error: %s", strerror(errno));
            }
            continue;
        }

        if (ioctl(g_v4l2Fd, VIDIOC_DQBUF, &buf) < 0) {
            if (errno == EAGAIN) continue;
            LOGE("VIDIOC_DQBUF failed: %s", strerror(errno));
            break;
        }

        frameCount++;

        // Log frame info
        if (frameCount <= 5 || frameCount % 30 == 0) {
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            double elapsed = (now.tv_sec - startTime.tv_sec) +
                           (now.tv_nsec - startTime.tv_nsec) / 1e9;
            double fps = frameCount / elapsed;

            LOGI("V4L2 Frame #%d: %d bytes, %.1f fps (%.1fs elapsed)",
                 frameCount, buf.bytesused, fps, elapsed);
        }

        // Send frame data to Java callback
        if (g_callbackObj && g_onFrameMethod && buf.bytesused > 0) {
            jbyteArray jdata = env->NewByteArray(buf.bytesused);
            env->SetByteArrayRegion(jdata, 0, buf.bytesused,
                                    (jbyte *)g_buffers[buf.index].start);
            env->CallVoidMethod(g_callbackObj, g_onFrameMethod,
                               jdata, (jint)buf.bytesused, (jint)frameCount);
            env->DeleteLocalRef(jdata);
        }

        // Re-queue buffer
        if (ioctl(g_v4l2Fd, VIDIOC_QBUF, &buf) < 0) {
            LOGE("VIDIOC_QBUF re-queue failed: %s", strerror(errno));
            break;
        }
    }

    LOGI("V4L2 capture thread ended (%d frames)", frameCount);
    g_jvm->DetachCurrentThread();
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xreal_hardware_XRealHardwareManager_nativeProbeV4L2(
    JNIEnv *env, jobject thiz) {

    LOGI("=== V4L2 RGB Camera Probe ===");
    int fd = findRGBCameraDevice();
    if (fd >= 0) {
        close(fd);
        return 0; // Found
    }
    return -1; // Not found
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xreal_hardware_XRealHardwareManager_nativeStartV4L2RGB(
    JNIEnv *env, jobject thiz) {

    if (g_streaming) {
        LOGI("V4L2 already streaming");
        return 0;
    }

    env->GetJavaVM(&g_jvm);

    LOGI("=== V4L2 RGB Camera Start ===");

    // Find and open device
    g_v4l2Fd = findRGBCameraDevice();
    if (g_v4l2Fd < 0) {
        LOGE("RGB camera V4L2 device not found!");
        return -1;
    }

    // Setup format
    if (setupFormat(g_v4l2Fd) < 0) {
        close(g_v4l2Fd);
        g_v4l2Fd = -1;
        return -2;
    }

    // Setup mmap buffers
    if (setupMmap(g_v4l2Fd) < 0) {
        close(g_v4l2Fd);
        g_v4l2Fd = -1;
        return -3;
    }

    // Get callback method
    g_callbackObj = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(thiz);
    g_onFrameMethod = env->GetMethodID(clazz, "onV4L2Frame", "([BII)V");
    if (!g_onFrameMethod) {
        LOGE("onV4L2Frame method not found!");
        // Continue anyway - just won't callback
    }

    // Start streaming
    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (ioctl(g_v4l2Fd, VIDIOC_STREAMON, &type) < 0) {
        LOGE("VIDIOC_STREAMON failed: %s", strerror(errno));
        close(g_v4l2Fd);
        g_v4l2Fd = -1;
        return -4;
    }

    LOGI("V4L2 STREAMON success!");

    // Start capture thread
    g_streaming = true;
    pthread_create(&g_captureThread, nullptr, captureThreadFunc, nullptr);

    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xreal_hardware_XRealHardwareManager_nativeStopV4L2RGB(
    JNIEnv *env, jobject thiz) {

    LOGI("=== V4L2 RGB Camera Stop ===");

    g_streaming = false;
    pthread_join(g_captureThread, nullptr);

    if (g_v4l2Fd >= 0) {
        enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        ioctl(g_v4l2Fd, VIDIOC_STREAMOFF, &type);

        for (int i = 0; i < g_numBuffers; i++) {
            if (g_buffers[i].start && g_buffers[i].start != MAP_FAILED) {
                munmap(g_buffers[i].start, g_buffers[i].length);
            }
        }

        close(g_v4l2Fd);
        g_v4l2Fd = -1;
    }

    if (g_callbackObj) {
        env->DeleteGlobalRef(g_callbackObj);
        g_callbackObj = nullptr;
    }

    LOGI("V4L2 RGB Camera stopped");
}
