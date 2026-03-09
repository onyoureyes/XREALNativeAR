#include <android/log.h>
#include <atomic>
#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define LOG_TAG "RGBIsoCam"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef USBDEVFS_URB_ISO_ASAP
#define USBDEVFS_URB_ISO_ASAP 0x02
#endif

// ── Configuration ──
// 단계적 축소: 먼저 큰 설정 시도, ENOMEM 시 작은 설정으로 재시도
static constexpr int NUM_URBS_MAX = 6;
static constexpr int PKTS_PER_URB_MAX = 32;
static int g_numUrbs = 6;
static int g_pktsPerUrb = 32;

// ── State ──
static int g_fd = -1;
static int g_ifaceNum = -1;
static int g_altSetting = -1;
static int g_endpoint = 0;
static int g_pktSize = 0;
static int g_maxFrameSize = 0;
static std::atomic<bool> g_running(false);
static pthread_t g_thread;
static JavaVM *g_jvm = nullptr;
static jobject g_callback = nullptr;
static jmethodID g_onFrameMethod = nullptr;

// ── URB management ──
// usbdevfs_urb has iso_frame_desc[] as flexible array member,
// so we must allocate urb + descriptors contiguously.
struct IsoUrbInfo {
    struct usbdevfs_urb *urb;  // heap-allocated, includes iso_frame_desc
    uint8_t *dataBuf;
    int dataBufSize;
};
static IsoUrbInfo g_urbs[NUM_URBS_MAX];

// ── Frame assembly ──
static uint8_t *g_frameBuf = nullptr;
static int g_framePos = 0;

// ── URB allocation ──
static void createIsoUrb(int index, int numPkts, int pktSize, int endpoint) {
    IsoUrbInfo *info = &g_urbs[index];

    size_t urbSize = sizeof(struct usbdevfs_urb) +
                     numPkts * sizeof(struct usbdevfs_iso_packet_desc);
    info->urb = (struct usbdevfs_urb *)calloc(1, urbSize);
    info->dataBufSize = numPkts * pktSize;
    info->dataBuf = (uint8_t *)calloc(1, info->dataBufSize);

    info->urb->type = USBDEVFS_URB_TYPE_ISO;
    info->urb->endpoint = endpoint;
    info->urb->flags = USBDEVFS_URB_ISO_ASAP;
    info->urb->buffer = info->dataBuf;
    info->urb->buffer_length = info->dataBufSize;
    info->urb->number_of_packets = numPkts;
    info->urb->usercontext = (void *)(intptr_t)index;

    for (int i = 0; i < numPkts; i++) {
        info->urb->iso_frame_desc[i].length = pktSize;
    }
}

static void freeIsoUrb(int index) {
    IsoUrbInfo *info = &g_urbs[index];
    if (info->urb) { free(info->urb); info->urb = nullptr; }
    if (info->dataBuf) { free(info->dataBuf); info->dataBuf = nullptr; }
}

static int submitUrb(int index) {
    int ret = ioctl(g_fd, USBDEVFS_SUBMITURB, g_urbs[index].urb);
    if (ret < 0) {
        LOGE("SUBMITURB[%d] failed: %s (errno=%d)", index, strerror(errno), errno);
    }
    return ret;
}

// ── Process reaped URB ──
// Extract UVC payloads from ISO packets, detect end-of-frame.
// Returns bytes copied into outBuf. Sets *eof=true on EOF.
static int processReapedUrb(IsoUrbInfo *info,
                            uint8_t *outBuf, int outBufSize,
                            bool *eof) {
    int totalBytes = 0;
    *eof = false;

    uint8_t *buf = info->dataBuf;
    int offset = 0;

    for (int i = 0; i < info->urb->number_of_packets; i++) {
        int actual = info->urb->iso_frame_desc[i].actual_length;
        int status = info->urb->iso_frame_desc[i].status;

        if (status != 0 || actual <= 0) {
            offset += info->urb->iso_frame_desc[i].length;
            continue;
        }

        // UVC payload header: byte 0 = header length, byte 1 = flags
        uint8_t *pkt = buf + offset;
        if (actual >= 2) {
            int headerLen = pkt[0] & 0xFF;
            int headerInfo = pkt[1] & 0xFF;

            if (headerLen < 2 || headerLen > actual) {
                offset += info->urb->iso_frame_desc[i].length;
                continue;
            }

            // EOF bit = bit 1 of bmHeaderInfo
            if (headerInfo & 0x02) {
                *eof = true;
            }

            int payloadLen = actual - headerLen;
            if (payloadLen > 0 && totalBytes + payloadLen <= outBufSize) {
                memcpy(outBuf + totalBytes, pkt + headerLen, payloadLen);
                totalBytes += payloadLen;
            }
        }

        offset += info->urb->iso_frame_desc[i].length;
    }

    return totalBytes;
}

// ── Capture thread ──
static void *captureThread(void * /*arg*/) {
    JNIEnv *env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach capture thread to JVM");
        return nullptr;
    }

    LOGI(">>> RGB ISO CAPTURE THREAD STARTED <<<");
    LOGI("  fd=%d iface=%d alt=%d ep=0x%02X pktSize=%d maxFrame=%d",
         g_fd, g_ifaceNum, g_altSetting, g_endpoint, g_pktSize, g_maxFrameSize);

    int extractBufSize = g_pktSize * g_pktsPerUrb;
    uint8_t *extractBuf = (uint8_t *)malloc(extractBufSize);

    // Submit all URBs — ENOMEM 시 점진적 축소 재시도
    int submitted = 0;
    for (int i = 0; i < g_numUrbs; i++) {
        if (submitUrb(i) == 0) submitted++;
    }
    LOGI("Submitted %d/%d URBs (pktsPerUrb=%d)", submitted, g_numUrbs, g_pktsPerUrb);

    // 전부 실패 시 URB 축소 후 재시도 (6→3→2, 32→16→8)
    if (submitted == 0) {
        static const int RETRY_URBS[]  = {3, 2, 2, 1};
        static const int RETRY_PKTS[]  = {32, 16, 8, 8};
        static const int RETRY_COUNT = 4;

        for (int r = 0; r < RETRY_COUNT && submitted == 0; r++) {
            int newUrbs = RETRY_URBS[r];
            int newPkts = RETRY_PKTS[r];
            LOGI("URB submit failed — retry %d: %d URBs × %d pkts", r+1, newUrbs, newPkts);

            // 기존 URB 해제
            for (int i = 0; i < g_numUrbs; i++) {
                freeIsoUrb(i);
            }
            g_numUrbs = newUrbs;
            g_pktsPerUrb = newPkts;

            // 새 URB 생성
            for (int i = 0; i < g_numUrbs; i++) {
                createIsoUrb(i, g_pktsPerUrb, g_pktSize, g_endpoint);
            }

            // 재시도
            for (int i = 0; i < g_numUrbs; i++) {
                if (submitUrb(i) == 0) submitted++;
            }
            LOGI("Retry %d: submitted %d/%d URBs (pkts=%d)", r+1, submitted, g_numUrbs, g_pktsPerUrb);
        }
    }

    if (submitted == 0) {
        LOGE("No URBs submitted after all retries — aborting capture");
        free(extractBuf);
        g_jvm->DetachCurrentThread();
        return nullptr;
    }

    int frameCount = 0;
    int errorCount = 0;
    struct timespec startTs;
    clock_gettime(CLOCK_MONOTONIC, &startTs);
    long lastLogMs = 0;
    int bytesThisSec = 0;
    int framesThisSec = 0;
    int emptyUrbCount = 0;

    while (g_running) {
        struct usbdevfs_urb *reaped = nullptr;
        int ret = ioctl(g_fd, USBDEVFS_REAPURB, &reaped);

        if (ret < 0) {
            if (errno == EAGAIN || errno == EINTR) continue;
            if (errno == ENODEV) {
                LOGE("USB device disconnected!");
                break;
            }
            errorCount++;
            if (errorCount <= 10 || errorCount % 100 == 0) {
                LOGE("REAPURB error: %s (errno=%d, count=%d)",
                     strerror(errno), errno, errorCount);
            }
            if (errorCount >= 200) {
                LOGE("Too many REAPURB errors — aborting");
                break;
            }
            usleep(1000);
            continue;
        }

        if (!reaped) continue;

        int urbIdx = (int)(intptr_t)reaped->usercontext;
        if (urbIdx < 0 || urbIdx >= g_numUrbs) {
            LOGE("Bad URB index: %d", urbIdx);
            continue;
        }

        // Process
        bool eof = false;
        int extracted = processReapedUrb(&g_urbs[urbIdx],
                                         extractBuf, extractBufSize, &eof);

        if (extracted > 0) {
            emptyUrbCount = 0;
            if (g_framePos + extracted <= g_maxFrameSize + 65536) {
                memcpy(g_frameBuf + g_framePos, extractBuf, extracted);
                g_framePos += extracted;
                bytesThisSec += extracted;
            }
        } else {
            emptyUrbCount++;
            // Log occasional empty URBs for diagnostics
            if (emptyUrbCount <= 5 || emptyUrbCount % 500 == 0) {
                // Dump first packet's status for debugging
                int st0 = g_urbs[urbIdx].urb->iso_frame_desc[0].status;
                int al0 = g_urbs[urbIdx].urb->iso_frame_desc[0].actual_length;
                LOGI("Empty URB[%d] (count=%d): pkt0 status=%d actual=%d",
                     urbIdx, emptyUrbCount, st0, al0);
            }
        }

        // Frame complete?
        if (eof && g_framePos > 0) {
            frameCount++;
            framesThisSec++;

            // Deliver to Java
            if (g_callback && g_onFrameMethod) {
                jbyteArray jdata = env->NewByteArray(g_framePos);
                env->SetByteArrayRegion(jdata, 0, g_framePos,
                                        (jbyte *)g_frameBuf);
                env->CallVoidMethod(g_callback, g_onFrameMethod,
                                    jdata, g_framePos, frameCount);
                env->DeleteLocalRef(jdata);
            }

            // Logging
            struct timespec nowTs;
            clock_gettime(CLOCK_MONOTONIC, &nowTs);
            long nowMs = (nowTs.tv_sec - startTs.tv_sec) * 1000 +
                         (nowTs.tv_nsec - startTs.tv_nsec) / 1000000;

            if (frameCount <= 5) {
                LOGI("RGB Frame #%d: %d bytes", frameCount, g_framePos);
                char hex[128];
                int hl = 0;
                for (int i = 0; i < 32 && i < g_framePos; i++) {
                    hl += snprintf(hex + hl, sizeof(hex) - hl,
                                   "%02X ", g_frameBuf[i] & 0xFF);
                }
                LOGI("  first32: %s", hex);
            }

            if (nowMs - lastLogMs >= 1000) {
                double fps = (nowMs > lastLogMs)
                    ? framesThisSec * 1000.0 / (nowMs - lastLogMs) : 0;
                LOGI("RGB ISO: #%d, %.1f fps, %d KB/s",
                     frameCount, fps, bytesThisSec / 1024);
                framesThisSec = 0;
                bytesThisSec = 0;
                lastLogMs = nowMs;
            }

            g_framePos = 0;
        }

        // Re-submit with retry
        if (g_running) {
            for (int j = 0; j < g_pktsPerUrb; j++) {
                g_urbs[urbIdx].urb->iso_frame_desc[j].actual_length = 0;
                g_urbs[urbIdx].urb->iso_frame_desc[j].status = 0;
            }
            int resubmitResult = submitUrb(urbIdx);
            if (resubmitResult < 0) {
                // 재제출 실패 → 짧은 대기 후 최대 5회 재시도
                for (int retry = 0; retry < 5 && g_running; retry++) {
                    usleep(5000 * (retry + 1));  // 5ms, 10ms, 15ms, 20ms, 25ms
                    resubmitResult = submitUrb(urbIdx);
                    if (resubmitResult == 0) {
                        LOGI("URB[%d] re-submit recovered after %d retries", urbIdx, retry + 1);
                        break;
                    }
                }
                if (resubmitResult < 0) {
                    LOGE("URB[%d] re-submit FAILED after 5 retries — stream may stall", urbIdx);
                    // 1초 대기 후 한 번 더 시도 (커널 DMA 해제 대기)
                    usleep(1000000);
                    if (g_running) {
                        resubmitResult = submitUrb(urbIdx);
                        if (resubmitResult == 0) {
                            LOGI("URB[%d] recovered after 1s cooldown", urbIdx);
                        } else {
                            LOGE("URB[%d] unrecoverable — breaking capture loop", urbIdx);
                            break;
                        }
                    }
                }
            }
        }
    }

    LOGI("RGB ISO capture ended (frames=%d, errors=%d)", frameCount, errorCount);
    free(extractBuf);
    g_jvm->DetachCurrentThread();
    return nullptr;
}

// ── JNI: Start ──
extern "C" JNIEXPORT jint JNICALL
Java_com_xreal_hardware_RGBCameraUVC_nativeStartIso(
    JNIEnv *env, jobject thiz,
    jint fd, jint ifaceNum, jint altSetting,
    jint endpoint, jint maxPktSize, jint maxFrameSize) {

    if (g_running) {
        LOGI("RGB ISO already running");
        return 0;
    }

    LOGI("=== nativeStartIso ===");
    LOGI("  fd=%d iface=%d alt=%d ep=0x%02X pktSize=%d maxFrame=%d",
         fd, ifaceNum, altSetting, endpoint, maxPktSize, maxFrameSize);

    env->GetJavaVM(&g_jvm);
    g_fd = fd;
    g_ifaceNum = ifaceNum;
    g_altSetting = altSetting;
    g_endpoint = endpoint;
    g_pktSize = maxPktSize;
    g_maxFrameSize = maxFrameSize;

    // Try to claim interface (may already be claimed from Java — that's OK)
    int iface = ifaceNum;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface) < 0) {
        LOGI("CLAIMINTERFACE: %s (errno=%d, probably already claimed)",
             strerror(errno), errno);
    } else {
        LOGI("Interface %d claimed from native", ifaceNum);
    }

    // Set alternate setting to activate ISOC bandwidth
    struct usbdevfs_setinterface si;
    si.interface = ifaceNum;
    si.altsetting = altSetting;
    int ret = ioctl(fd, USBDEVFS_SETINTERFACE, &si);
    if (ret < 0) {
        LOGE("SETINTERFACE(%d, alt=%d) FAILED: %s (errno=%d)",
             ifaceNum, altSetting, strerror(errno), errno);
        return -1;
    }
    LOGI("SETINTERFACE(%d, alt=%d) OK", ifaceNum, altSetting);

    // Allocate frame assembly buffer
    g_frameBuf = (uint8_t *)calloc(1, maxFrameSize + 65536);
    g_framePos = 0;

    // Java callback
    g_callback = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(thiz);
    g_onFrameMethod = env->GetMethodID(clazz, "onNativeIsoFrame", "([BII)V");
    if (!g_onFrameMethod) {
        LOGE("onNativeIsoFrame not found!");
    }

    // Create URBs
    g_numUrbs = NUM_URBS_MAX;
    g_pktsPerUrb = PKTS_PER_URB_MAX;
    for (int i = 0; i < g_numUrbs; i++) {
        createIsoUrb(i, g_pktsPerUrb, maxPktSize, endpoint);
    }

    // Start capture thread
    g_running = true;
    ret = pthread_create(&g_thread, nullptr, captureThread, nullptr);
    if (ret != 0) {
        LOGE("pthread_create failed: %s", strerror(ret));
        g_running = false;
        return -2;
    }

    LOGI("RGB ISO capture started");
    return 0;
}

// ── JNI: Stop ──
extern "C" JNIEXPORT void JNICALL
Java_com_xreal_hardware_RGBCameraUVC_nativeStopIso(
    JNIEnv *env, jobject thiz) {

    if (!g_running) return;

    LOGI("=== nativeStopIso ===");
    g_running = false;

    // Discard pending URBs to unblock REAPURB
    for (int i = 0; i < g_numUrbs; i++) {
        if (g_urbs[i].urb) {
            ioctl(g_fd, USBDEVFS_DISCARDURB, g_urbs[i].urb);
        }
    }

    pthread_join(g_thread, nullptr);

    // Reset to zero-bandwidth alt setting
    if (g_ifaceNum >= 0) {
        struct usbdevfs_setinterface si;
        si.interface = g_ifaceNum;
        si.altsetting = 0;
        ioctl(g_fd, USBDEVFS_SETINTERFACE, &si);
        LOGI("Interface %d reset to alt 0", g_ifaceNum);
    }

    // Free URBs
    for (int i = 0; i < g_numUrbs; i++) {
        freeIsoUrb(i);
    }

    if (g_frameBuf) {
        free(g_frameBuf);
        g_frameBuf = nullptr;
    }

    if (g_callback) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }

    LOGI("RGB ISO camera stopped");
}
