#!/bin/bash
# build_llama_android.sh — llama.cpp Android arm64-v8a 크로스 컴파일 + 프로젝트 복사
#
# 사용법:
#   bash scripts/build_llama_android.sh
#
# 필요:
#   - Android NDK 29.0.14206865 (D:/Sdk/ndk/29.0.14206865)
#   - llama.cpp 소스 (F:/llama.cpp)
#   - CMake 3.22.1+

set -e

# ── 경로 설정 ──
LLAMA_SRC="F:/llama.cpp"
NDK_PATH="D:/Sdk/ndk/29.0.14206865"
PROJECT_CPP="D:/XREALNativeAR/XREALNativeAR/app/src/main/cpp"
OUTPUT_DIR="${PROJECT_CPP}/llama_cpp"
BUILD_DIR="${LLAMA_SRC}/build-android-arm64"
ABI="arm64-v8a"
ANDROID_API=26

echo "=== llama.cpp Android arm64-v8a 빌드 시작 ==="
echo "  소스: ${LLAMA_SRC}"
echo "  NDK:  ${NDK_PATH}"
echo "  출력: ${OUTPUT_DIR}"

# ── 빌드 디렉토리 ──
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

# ── CMake 크로스 컴파일 ──
cmake -G Ninja -S "${LLAMA_SRC}" -B "${BUILD_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK_PATH}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DGGML_OPENMP=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_METAL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DBUILD_SHARED_LIBS=OFF

cmake --build "${BUILD_DIR}" --config Release -j$(nproc 2>/dev/null || echo 8)

echo "=== 빌드 완료 ==="

# ── 정적 라이브러리 + 헤더 복사 ──
mkdir -p "${OUTPUT_DIR}/lib/${ABI}"
mkdir -p "${OUTPUT_DIR}/include"

# 정적 라이브러리 복사 (llama.cpp 빌드 구조에 따라 위치가 다를 수 있음)
echo "정적 라이브러리 복사 중..."
for lib in libllama.a libggml.a libggml-base.a libggml-cpu.a libcommon.a; do
    found=$(find "${BUILD_DIR}" -name "${lib}" -type f 2>/dev/null | head -1)
    if [ -n "${found}" ]; then
        cp "${found}" "${OUTPUT_DIR}/lib/${ABI}/"
        echo "  복사: ${found} → ${OUTPUT_DIR}/lib/${ABI}/${lib}"
    else
        echo "  경고: ${lib} 없음 (선택적 라이브러리일 수 있음)"
    fi
done

# 헤더 복사
echo "헤더 복사 중..."
cp "${LLAMA_SRC}/include/"*.h "${OUTPUT_DIR}/include/"
# ggml 헤더 전체 복사 (ggml.h, ggml-cpu.h, ggml-backend.h 등)
cp "${LLAMA_SRC}/ggml/include/"*.h "${OUTPUT_DIR}/include/"

echo ""
echo "=== 완료 ==="
echo "출력 디렉토리: ${OUTPUT_DIR}"
echo ""
ls -la "${OUTPUT_DIR}/lib/${ABI}/"
echo ""
ls -la "${OUTPUT_DIR}/include/"
echo ""
echo "다음 단계: ./gradlew :app:assembleDebug"
