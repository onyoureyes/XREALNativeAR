cmake_minimum_required(VERSION 3.22.1)

project("xreal-hardware")

add_library(${CMAKE_PROJECT_NAME} SHARED
    native_hw.cpp
    xreal_cam_driver.cpp
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
)
