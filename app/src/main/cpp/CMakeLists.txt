cmake_minimum_required(VERSION 3.22.1)
project("xreal_native_ar")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp pre-built static libraries path
# 빌드 방법: scripts/build_llama_android.sh 실행 → 정적 라이브러리 + 헤더 복사
set(LLAMA_CPP_DIR "${CMAKE_SOURCE_DIR}/llama_cpp" CACHE PATH "Path to pre-built llama.cpp")

# Include headers
include_directories(
    ${LLAMA_CPP_DIR}/include
    ${CMAKE_SOURCE_DIR}
)

# Add native source files
add_library(
        xreal_native_ar
        SHARED
        native_bridge.cpp
        llama_bridge.cpp
)

# llama.cpp static libraries (pre-built for arm64-v8a)
add_library(llama STATIC IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION ${LLAMA_CPP_DIR}/lib/${ANDROID_ABI}/libllama.a
)

add_library(ggml STATIC IMPORTED)
set_target_properties(ggml PROPERTIES
    IMPORTED_LOCATION ${LLAMA_CPP_DIR}/lib/${ANDROID_ABI}/libggml.a
)

# ggml-base (llama.cpp b5000+ 빌드 구조)
add_library(ggml-base STATIC IMPORTED)
set_target_properties(ggml-base PROPERTIES
    IMPORTED_LOCATION ${LLAMA_CPP_DIR}/lib/${ANDROID_ABI}/libggml-base.a
)

# ggml-cpu (CPU backend)
add_library(ggml-cpu STATIC IMPORTED)
set_target_properties(ggml-cpu PROPERTIES
    IMPORTED_LOCATION ${LLAMA_CPP_DIR}/lib/${ANDROID_ABI}/libggml-cpu.a
)

# common (llama.cpp 유틸)
add_library(llama-common STATIC IMPORTED)
set_target_properties(llama-common PROPERTIES
    IMPORTED_LOCATION ${LLAMA_CPP_DIR}/lib/${ANDROID_ABI}/libcommon.a
)

# Link libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
    # llama.cpp (순서 중요 — 의존성 역순)
    llama
    llama-common
    ggml
    ggml-cpu
    ggml-base
    android
    log
)
