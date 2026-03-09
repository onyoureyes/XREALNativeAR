#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <queue>
#include <thread>

// Forward declarations (avoid pulling full OpenVINS headers)
namespace ov_msckf {
class VioManager;
struct VioManagerOptions;
} // namespace ov_msckf

/**
 * VIOBridge — thin C++ wrapper around OpenVINS VioManager.
 *
 * Thread-safe: feedIMU() and feedStereoFrame() can be called
 * from any thread. Processing runs on a dedicated worker thread.
 *
 * All timestamps must be in seconds (device microseconds / 1e6).
 */
class VIOBridge {
public:
    struct Pose6DoF {
        double x, y, z;           // position in global frame (meters)
        double qx, qy, qz, qw;   // orientation quaternion (Hamilton convention)
        double timestamp;          // seconds
    };

    using PoseCallback = std::function<void(const Pose6DoF &)>;

    VIOBridge();
    ~VIOBridge();

    /**
     * Initialize OpenVINS with hardcoded XREAL Light calibration.
     * Must be called before start().
     * @return true if initialization succeeded, false on failure.
     */
    bool initialize();

    /**
     * Feed raw IMU measurement.
     * @param gx,gy,gz  Gyroscope angular velocity (rad/s)
     * @param ax,ay,az  Accelerometer linear acceleration (m/s²)
     * @param timestamp_sec  Device timestamp in seconds
     */
    void feedIMU(double gx, double gy, double gz,
                 double ax, double ay, double az,
                 double timestamp_sec);

    /**
     * Feed pre-rectified stereo grayscale frames.
     * Images are copied internally (caller can reuse buffers).
     * @param left,right  Grayscale image data (row-major)
     * @param width,height  Image dimensions
     * @param timestamp_sec  Device timestamp in seconds
     */
    void feedStereoFrame(const uint8_t *left, const uint8_t *right,
                         int width, int height,
                         double timestamp_sec);

    /** Start processing thread. */
    void start();

    /** Stop processing thread and release resources. */
    void stop();

    /** Register callback for 6-DoF pose updates. */
    void setPoseCallback(PoseCallback cb);

    /** Whether OpenVINS has initialized (needs motion + features). */
    bool isInitialized() const;

private:
    // IMU queue (lock-free is overkill; simple mutex is fine at 1kHz)
    struct ImuSample {
        double timestamp;
        double wm[3]; // gyro
        double am[3]; // accel
    };

    // Stereo frame (latest-only, drop old)
    struct StereoFrame {
        double timestamp;
        std::vector<uint8_t> left;
        std::vector<uint8_t> right;
        int width, height;
    };

    void workerLoop();

    std::unique_ptr<ov_msckf::VioManager> vio_;
    PoseCallback poseCallback_;

    // IMU queue
    std::mutex imuMtx_;
    std::queue<ImuSample> imuQueue_;

    // Latest stereo frame (overwrite policy)
    std::mutex frameMtx_;
    std::unique_ptr<StereoFrame> latestFrame_;
    bool hasNewFrame_ = false;

    // Worker thread
    std::atomic<bool> running_{false};
    std::thread workerThread_;
};
