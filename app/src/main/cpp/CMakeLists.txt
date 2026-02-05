cmake_minimum_required(VERSION 3.22.1)
project("xreal_native_ar")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add QNN SDK path
set(QNN_SDK_ROOT "C:/Qualcomm/AIStack/QAIRT/2.31.0.250130" CACHE PATH "Path to QAIRT SDK")

# Include QNN headers
include_directories(
    ${QNN_SDK_ROOT}/include
    ${QNN_SDK_ROOT}/include/QNN
    ${CMAKE_SOURCE_DIR}
)

# Add native source files
add_library(
        xreal_native_ar
        SHARED
        native_bridge.cpp
        qnn_runtime.cpp
        yolo_detector.cpp
        xreal_camera.cpp
        # Plan A only for now
        # stereo_camera.cpp  # TODO: Requires libuvc
        # nrsdk_bridge.cpp    # TODO: Requires GLM
)

# Link QNN libraries
add_library(QnnHtp SHARED IMPORTED)
set_target_properties(QnnHtp PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libQnnHtp.so
)

add_library(QnnSystem SHARED IMPORTED)
set_target_properties(QnnSystem PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libQnnSystem.so
)

# Link libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
    # QnnHtp      <-- REMOVED: Load via dlopen only
    # QnnSystem   <-- REMOVED
    android
    log
    GLESv3
    camera2ndk
    mediandk
)
