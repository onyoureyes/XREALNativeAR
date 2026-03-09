cmake_minimum_required(VERSION 3.22.1)

project("vulkan_renderer")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Vulkan from NDK ──
find_library(vulkan-lib vulkan)

# ── vk-bootstrap (vendored, MIT license) ──
add_library(vk_bootstrap STATIC
    third_party/vk_bootstrap/VkBootstrap.cpp
)
target_include_directories(vk_bootstrap PUBLIC
    third_party/vk_bootstrap
)

# ── Main Vulkan renderer library ──
add_library(${CMAKE_PROJECT_NAME} SHARED
    vulkan_renderer_jni.cpp
    vulkan_context.cpp
    vulkan_pipeline.cpp
    camera_background.cpp
    mesh_renderer.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(${CMAKE_PROJECT_NAME}
    ${android-lib}
    ${log-lib}
    ${vulkan-lib}
    vk_bootstrap
)
