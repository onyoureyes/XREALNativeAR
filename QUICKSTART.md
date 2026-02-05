# 🚀 Quick Start: 처음부터 끝까지

Unity에서 YOLO 모델을 이미 가지고 있으므로, 빠른 시작이 가능합니다!

## Step 1: Android Studio 설치 (30분)

1. https://developer.android.com/studio 에서 다운로드
2. 설치 후 실행
3. `Tools > SDK Manager` 열기
4. **SDK Tools** 탭에서 체크:
   - ✅ NDK (Side by side)
   - ✅ CMake
   - ✅ Android SDK Build-Tools 34
5. **Apply** 클릭 → 다운로드 대기

---

## Step 2: Qualcomm QNN SDK 다운로드 (20분)

### Option A: QPM 사용 (쉬움)
```
1. https://qpm.qualcomm.com/ 가입 (무료)
2. QPM 설치: https://qpm.qualcomm.com/#/main/tools/details/QPM3
3. QPM 실행 → "QNN SDK" 검색
4. QNN SDK v2.28 다운로드
5. D:\QualcommSDK\qnn-v2.28 에 압축 해제
```

### Option B: 웹에서 직접 다운로드
```
1. https://developer.qualcomm.com/software/qualcomm-ai-engine-direct-sdk
2. "Download" 클릭 (Qualcomm 계정 필요)
3. Windows 버전 선택
4. 압축 해제: D:\QualcommSDK\
```

---

## Step 3: YOLO 모델 변환 (10분)

이미 `F:\UnityProject\My project\Assets\Models\yolov5s.onnx` 있음!

```powershell
# 1. QNN Converter PATH 추가
$env:Path += ";D:\QualcommSDK\qnn-v2.28\bin\x86_64-windows-msvc"

# 2. Unity 모델을 프로젝트로 복사
cd D:\Project_Jarvis\XREALNativeAR
cp "F:\UnityProject\My project\Assets\Models\yolov5s.onnx" .\

# 3. ONNX → DLC 변환
qnn-onnx-converter `
  --input_network yolov5s.onnx `
  --output_path yolov5s.dlc `
  --input_dim input "1,3,300,300"

# 4. INT8 양자화 (NPU 가속)
# (sample data 생성 필요 - MODEL_CONVERSION_GUIDE.md 참고)

# 5. Assets 폴더로 복사
cp yolov5s.dlc app\src\main\assets\
```

---

## Step 4: QNN 라이브러리 복사 (5분)

```powershell
cd D:\Project_Jarvis\XREALNativeAR

# Android ARM64 라이브러리 복사
cp D:\QualcommSDK\qnn-v2.28\lib\aarch64-android\*.so libs\
```

필요한 파일들:
- `libQnnHtp.so` (Hexagon NPU)
- `libQnnSystem.so`
- `libQnnHtpV##Stub.so` (버전에 따라 다름)

---

## Step 5: Android Studio에서 프로젝트 열기 (5분)

```
1. Android Studio 실행
2. "Open" 클릭
3. D:\Project_Jarvis\XREALNativeAR 선택
4. "Trust Project" 클릭
5. Gradle Sync 완료 대기 (처음엔 시간 걸림)
```

### CMake 경로 수정
`app/src/main/cpp/CMakeLists.txt` 열기:

```cmake
# 3번째 줄을 실제 경로로 수정
set(QNN_SDK_ROOT "D:/QualcommSDK/qnn-v2.28" CACHE PATH "Path to QNN SDK")
```

---

## Step 6: C++ 코드 완성 (QNN SDK 설치 후)

현재 C++ 코드는 placeholder입니다. QNN SDK 예제를 참고해서 완성해야 해요.

### 주요 파일들:
1. `qnn_runtime.cpp` - QNN 초기화 & 모델 로드
2. `yolo_detector.cpp` - YOLO 추론 (이미 완성됨)
3. `xreal_camera.cpp` - Camera2 API로 XREAL 카메라 접근

### QNN 예제 참고:
```
D:\QualcommSDK\qnn-v2.28\examples\QNN\SampleApp\
```

---

## Step 7: 빌드 & 테스트

```
1. Build > Make Project (Ctrl+F9)
2. 에러 확인 → 수정
3. 갤럭시 폴드4 USB 연결
4. Run > Run 'app' (Shift+F10)
```

### 첫 실행 시:
- 카메라 권한 허용
- "NPU Ready!" 토스트 확인
- Logcat에서 로그 확인:
  ```
  adb logcat -s XREALNativeAR
  ```

---

## 예상 Timeline

| 단계 | 예상 시간 | 설명 |
|------|-----------|------|
| Android Studio 설치 | 30분 | NDK, CMake 포함 |
| QNN SDK 다운로드 | 20분 | 네트워크 속도 의존 |
| 모델 변환 | 10분 | ONNX → DLC |
| 프로젝트 설정 | 10분 | 라이브러리 복사, CMake 수정 |
| **첫 빌드 성공** | **70분** | |
| C++ QNN 코드 완성 | 2-3일 | QNN SDK 문서 학습 필요 |
| Camera2 통합 | 1-2일 | XREAL USB 카메라 접근 |
| AR 렌더링 | 1-2일 | OpenGL ES로 라벨 그리기 |
| **완성품** | **1-2주** | |

---

## 현재 프로젝트 상태

✅ **완료**:
- Android 프로젝트 구조
- Gradle 빌드 설정
- JNI 브릿지 코드
- YOLO 감지 로직 (C++)
- Kotlin UI

⏳ **사용자 작업 필요**:
- Android Studio 설치
- QNN SDK 다운로드
- 모델 변환
- 라이브러리 복사

🔜 **다음 단계**:
- QNN API 실제 구현
- Camera2 통합
- AR 오버레이

---

## 🆘 도움이 필요하면

SDK 설치 & 모델 변환 완료 후:

1. **QNN 코드 작성 도움** - SDK 문서 기반 구현
2. **CMake 에러 해결** - 빌드 문제 디버깅
3. **Camera2 통합** - XREAL USB 카메라 연결
4. **성능 최적화** - INT8 양자화, 배치 처리

알려주세요! 🚀

---

## Unity vs Native 성능 예상

| | Unity + GPU | Native + NPU |
|---|-------------|--------------|
| 추론 속도 | 100-200ms | 10-30ms ⚡ |
| FPS | 5-10 fps | 30+ fps 🎮 |
| 배터리 | 30%/시간 | 3-5%/시간 🔋 |
| 발열 | 높음 🔥 | 낮음 ❄️ |

**결론**: NPU 사용 시 **10배 빠르고**, **배터리 90% 절약**!
