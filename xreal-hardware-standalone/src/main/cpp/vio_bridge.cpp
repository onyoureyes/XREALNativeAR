#include "vio_bridge.h"

#include <android/log.h>
#include <chrono>

// OpenVINS headers
#include "core/VioManager.h"
#include "core/VioManagerOptions.h"
#include "state/State.h"
#include "types/IMU.h"
#include "utils/sensor_data.h"
#include "cam/CamRadtan.h"
#include "utils/quat_ops.h"

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#define TAG "VIOBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

VIOBridge::VIOBridge() = default;

VIOBridge::~VIOBridge() {
    stop();
}

bool VIOBridge::initialize() {
    LOGI("=== VIOBridge::initialize ===");

    try {

    ov_msckf::VioManagerOptions params;

    // ── State options ──
    params.state_options.num_cameras = 2;
    params.state_options.do_fej = true;
    params.state_options.do_calib_camera_pose = true;    // online cam-IMU extrinsic refinement
    params.state_options.do_calib_camera_intrinsics = false; // pre-rectified, skip
    params.state_options.do_calib_camera_timeoffset = true;  // refine IMU-cam dt
    params.state_options.do_calib_imu_intrinsics = false;
    params.state_options.do_calib_imu_g_sensitivity = false;
    params.state_options.max_clone_size = 8;
    params.state_options.max_slam_features = 25;
    params.state_options.max_slam_in_update = 999;
    params.state_options.max_msckf_in_update = 999;
    params.state_options.max_aruco_features = 0; // disabled
    params.state_options.feat_rep_msckf = ov_type::LandmarkRepresentation::Representation::GLOBAL_3D;
    params.state_options.feat_rep_slam = ov_type::LandmarkRepresentation::Representation::GLOBAL_3D;

    // ── IMU noise parameters (conservative starting values) ──
    params.imu_noises.sigma_w = 1.6968e-04;   // gyro noise density (rad/s/sqrt(Hz))
    params.imu_noises.sigma_wb = 1.9393e-05;  // gyro random walk (rad/s^2/sqrt(Hz))
    params.imu_noises.sigma_a = 2.0e-03;      // accel noise density (m/s^2/sqrt(Hz))
    params.imu_noises.sigma_ab = 3.0e-03;     // accel random walk (m/s^3/sqrt(Hz))

    // ── Updater options ──
    params.msckf_options.chi2_multipler = 5;
    params.slam_options.chi2_multipler = 5;
    params.zupt_options.chi2_multipler = 2;

    // ── Zero velocity update ──
    params.try_zupt = true;
    params.zupt_max_velocity = 0.5;
    params.zupt_noise_multiplier = 10.0;
    params.zupt_max_disparity = 0.5;
    params.zupt_only_at_beginning = false;

    // ── Initializer options ──
    params.init_options.init_window_time = 1.0;       // 1s of data for init
    params.init_options.init_imu_thresh = 1.5;        // require some motion
    params.init_options.init_max_features = 50;

    // ── Camera intrinsics (pre-rectified via StereoRectifier) ──
    // Resolution: 320x240 (downscaled from 640x480)
    // Rectified P1/P2 intrinsics scaled by 0.5:
    //   fx=fy=175.354, cx=160.023, cy=127.861
    int cam_w = 320;
    int cam_h = 240;
    double fx = 175.354;
    double fy = 175.354;
    double cx = 160.023;
    double cy = 127.861;

    // cam0 (left)
    Eigen::VectorXd cam0_calib = Eigen::VectorXd::Zero(8);
    cam0_calib << fx, fy, cx, cy, 0, 0, 0, 0; // zero distortion (pre-rectified)
    auto cam0 = std::make_shared<ov_core::CamRadtan>(cam_w, cam_h);
    cam0->set_value(cam0_calib);
    params.camera_intrinsics.insert({0, cam0});

    // cam1 (right) — same intrinsics for rectified pair
    Eigen::VectorXd cam1_calib = Eigen::VectorXd::Zero(8);
    cam1_calib << fx, fy, cx, cy, 0, 0, 0, 0;
    auto cam1 = std::make_shared<ov_core::CamRadtan>(cam_w, cam_h);
    cam1->set_value(cam1_calib);
    params.camera_intrinsics.insert({1, cam1});

    // ── Camera-IMU extrinsics ──
    // T_CtoI: transform from camera frame to IMU frame
    // IMU is approximately centered between the two cameras.
    // Baseline = 104mm, so cam0 is ~52mm left of IMU, cam1 is ~52mm right.
    // OpenVINS convention: [qw, qx, qy, qz, px, py, pz] (JPL quaternion)
    // Assuming cameras and IMU are roughly aligned (identity rotation to start):
    Eigen::Matrix<double, 7, 1> cam0_eigen, cam1_eigen;
    // cam0 (left): T_C0toI = identity rotation, translation [-0.052, 0, 0]
    cam0_eigen << 1.0, 0.0, 0.0, 0.0, -0.052, 0.0, 0.0;
    // cam1 (right): T_C1toI = identity rotation, translation [+0.052, 0, 0]
    cam1_eigen << 1.0, 0.0, 0.0, 0.0,  0.052, 0.0, 0.0;
    params.camera_extrinsics.insert({0, cam0_eigen});
    params.camera_extrinsics.insert({1, cam1_eigen});

    // ── Tracking parameters ──
    params.use_stereo = true;
    params.use_klt = true;
    params.use_aruco = false;
    params.num_pts = 150;
    params.fast_threshold = 15;
    params.grid_x = 5;
    params.grid_y = 3;
    params.min_px_dist = 8;
    params.knn_ratio = 0.70;
    params.downsample_cameras = false;  // already 320x240
    params.track_frequency = 15.0;      // 15fps effective
    params.num_opencv_threads = 2;      // limited on mobile

    // ── Feature initialization ──
    params.featinit_options.triangulate_1d = false;
    params.featinit_options.refine_features = true;
    params.featinit_options.max_runs = 5;
    params.featinit_options.min_dist = 3;
    params.featinit_options.max_baseline = 40;
    params.featinit_options.max_cond_number = 10000;

    // ── Time offset (will be estimated online) ──
    params.calib_camimu_dt = 0.0;

    // ── Gravity magnitude ──
    params.gravity_mag = 9.81;

    // ── Timing recording (disabled on Android) ──
    params.record_timing_information = false;

    // ── Create VioManager ──
    LOGI("Creating OpenVINS VioManager...");
    LOGI("  num_cameras=%d, intrinsics.size=%zu, extrinsics.size=%zu",
         params.state_options.num_cameras,
         params.camera_intrinsics.size(),
         params.camera_extrinsics.size());
    vio_ = std::make_unique<ov_msckf::VioManager>(params);
    LOGI("OpenVINS VioManager created successfully");

    return true;

    } catch (const std::exception &e) {
        LOGE("VIOBridge::initialize() failed: %s", e.what());
        vio_.reset();
        return false;
    } catch (...) {
        LOGE("VIOBridge::initialize() failed with unknown exception");
        vio_.reset();
        return false;
    }
}

void VIOBridge::feedIMU(double gx, double gy, double gz,
                        double ax, double ay, double az,
                        double timestamp_sec) {
    ImuSample sample;
    sample.timestamp = timestamp_sec;
    sample.wm[0] = gx; sample.wm[1] = gy; sample.wm[2] = gz;
    sample.am[0] = ax; sample.am[1] = ay; sample.am[2] = az;

    std::lock_guard<std::mutex> lock(imuMtx_);
    imuQueue_.push(sample);

    // Cap queue size to prevent unbounded growth if frames stall
    while (imuQueue_.size() > 5000) {
        imuQueue_.pop();
    }
}

void VIOBridge::feedStereoFrame(const uint8_t *left, const uint8_t *right,
                                int width, int height,
                                double timestamp_sec) {
    auto frame = std::make_unique<StereoFrame>();
    frame->timestamp = timestamp_sec;
    frame->width = width;
    frame->height = height;

    int sz = width * height;
    frame->left.assign(left, left + sz);
    frame->right.assign(right, right + sz);

    std::lock_guard<std::mutex> lock(frameMtx_);
    latestFrame_ = std::move(frame);
    hasNewFrame_ = true;
}

void VIOBridge::start() {
    if (running_) return;
    if (!vio_) {
        LOGE("VIOBridge::start() called before initialize()!");
        return;
    }
    LOGI("VIOBridge::start()");
    running_ = true;
    workerThread_ = std::thread(&VIOBridge::workerLoop, this);
}

void VIOBridge::stop() {
    if (!running_) return;
    LOGI("VIOBridge::stop()");
    running_ = false;
    if (workerThread_.joinable()) {
        workerThread_.join();
    }
    vio_.reset();
    LOGI("VIOBridge stopped");
}

void VIOBridge::setPoseCallback(PoseCallback cb) {
    poseCallback_ = std::move(cb);
}

bool VIOBridge::isInitialized() const {
    return vio_ && vio_->initialized();
}

void VIOBridge::workerLoop() {
    LOGI("VIO worker thread started");

    int frameCount = 0;
    auto lastLogTime = std::chrono::steady_clock::now();

    while (running_) {
        // 1. Drain IMU queue → feed to OpenVINS
        {
            std::lock_guard<std::mutex> lock(imuMtx_);
            while (!imuQueue_.empty()) {
                auto &s = imuQueue_.front();
                ov_core::ImuData imu;
                imu.timestamp = s.timestamp;
                imu.wm << s.wm[0], s.wm[1], s.wm[2];
                imu.am << s.am[0], s.am[1], s.am[2];
                vio_->feed_measurement_imu(imu);
                imuQueue_.pop();
            }
        }

        // 2. Check for new stereo frame
        std::unique_ptr<StereoFrame> frame;
        {
            std::lock_guard<std::mutex> lock(frameMtx_);
            if (hasNewFrame_) {
                frame = std::move(latestFrame_);
                hasNewFrame_ = false;
            }
        }

        if (frame) {
            // Downscale 640x480 → 320x240 if needed
            cv::Mat leftFull(frame->height, frame->width, CV_8UC1, frame->left.data());
            cv::Mat rightFull(frame->height, frame->width, CV_8UC1, frame->right.data());

            cv::Mat leftImg, rightImg;
            if (frame->width > 320) {
                cv::resize(leftFull, leftImg, cv::Size(320, 240));
                cv::resize(rightFull, rightImg, cv::Size(320, 240));
            } else {
                leftImg = leftFull;
                rightImg = rightFull;
            }

            // Build CameraData
            ov_core::CameraData cam_data;
            cam_data.timestamp = frame->timestamp;
            cam_data.sensor_ids = {0, 1};
            cam_data.images = {leftImg.clone(), rightImg.clone()};
            cam_data.masks = {cv::Mat::zeros(leftImg.rows, leftImg.cols, CV_8UC1),
                              cv::Mat::zeros(rightImg.rows, rightImg.cols, CV_8UC1)};

            // Feed to OpenVINS (this does tracking + MSCKF update internally)
            vio_->feed_measurement_camera(cam_data);
            frameCount++;

            // Extract pose if initialized
            if (vio_->initialized() && poseCallback_) {
                auto state = vio_->get_state();
                // OpenVINS uses JPL quaternion: [qx, qy, qz, qw] internally
                Eigen::Vector4d q_jpl = state->_imu->quat();
                Eigen::Vector3d pos = state->_imu->pos();

                // Convert JPL quaternion to Hamilton convention
                // JPL: q = [qx, qy, qz, qw], rotation R = R(q)^T compared to Hamilton
                // Actually OpenVINS stores as [x,y,z,w] in JPL.
                // For Hamilton: negate the vector part: qH = [-qx, -qy, -qz, qw]
                // OR equivalently, since both represent same rotation differently,
                // the JPL q_GtoI is equivalent to Hamilton q_ItoG.
                Pose6DoF pose;
                pose.x = pos(0);
                pose.y = pos(1);
                pose.z = pos(2);
                // JPL q stored as [x,y,z,w] represents rotation from Global to IMU
                // Hamilton convention: q_ItoG = conjugate of JPL q_GtoI
                pose.qx = -q_jpl(0);
                pose.qy = -q_jpl(1);
                pose.qz = -q_jpl(2);
                pose.qw =  q_jpl(3);
                pose.timestamp = state->_timestamp;

                poseCallback_(pose);
            }

            // Periodic logging
            auto now = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - lastLogTime).count();
            if (elapsed >= 5) {
                LOGI("VIO: frames=%d, initialized=%d", frameCount, vio_->initialized() ? 1 : 0);
                lastLogTime = now;
            }
        } else {
            // No frame available — sleep briefly to avoid busy-waiting
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }

    LOGI("VIO worker thread ended (frames=%d)", frameCount);
}
