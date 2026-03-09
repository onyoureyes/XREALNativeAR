cmake_minimum_required(VERSION 3.22.1)

project("xreal-hardware")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Third-party paths ──
set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party")

# ── Eigen3 ──
set(Eigen3_DIR "${THIRD_PARTY}/eigen3/cmake-config")
find_package(Eigen3 REQUIRED)

# ── OpenCV Android SDK (native) ──
set(OpenCV_DIR "${THIRD_PARTY}/opencv-android/OpenCV-android-sdk/sdk/native/jni")
find_package(OpenCV REQUIRED)

# ── Ceres (prebuilt static lib) ──
set(CERES_ROOT "${THIRD_PARTY}/ceres-solver")
add_library(ceres STATIC IMPORTED)
set_target_properties(ceres PROPERTIES
    IMPORTED_LOCATION "${CERES_ROOT}/build-android/lib/libceres.a"
    INTERFACE_INCLUDE_DIRECTORIES "${CERES_ROOT}/include;${CERES_ROOT}/internal/ceres/miniglog;${CERES_ROOT}/build-android/include"
)

# ── OpenVINS (prebuilt static lib) ──
set(OPENVINS_ROOT "${THIRD_PARTY}/open_vins")
add_library(ov_msckf STATIC IMPORTED)
set_target_properties(ov_msckf PROPERTIES
    IMPORTED_LOCATION "${OPENVINS_ROOT}/build-android/build/libov_msckf_lib.a"
)

# ── Boost shim headers (filesystem/datetime/math → C++17) ──
set(BOOST_SHIM "${THIRD_PARTY}/boost_shim")

# ── xreal-hardware shared library ──
add_library(${CMAKE_PROJECT_NAME} SHARED
    native_hw.cpp
    xreal_cam_driver.cpp
    v4l2_rgb_camera.cpp
    usb_iso_camera.cpp
    vio_bridge.cpp
    vio_jni.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${BOOST_SHIM}
    ${OPENVINS_ROOT}/ov_core/src
    ${OPENVINS_ROOT}/ov_init/src
    ${OPENVINS_ROOT}/ov_msckf/src
    ${EIGEN3_INCLUDE_DIR}
    ${OpenCV_INCLUDE_DIRS}
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    ROS_AVAILABLE=0
    ENABLE_ARUCO_TAGS=0
)

find_library(log-lib log)
find_library(camera2ndk-lib camera2ndk)
find_library(mediandk-lib mediandk)
find_library(nativewindow-lib nativewindow)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    ${log-lib}
    ${camera2ndk-lib}
    ${mediandk-lib}
    ${nativewindow-lib}
    ov_msckf
    ceres
    ${OpenCV_LIBS}
    Eigen3::Eigen
)
