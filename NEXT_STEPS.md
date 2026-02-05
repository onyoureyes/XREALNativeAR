# ✅ Phase 1 Complete: Android Project Structure Ready!

## What We've Built

완전한 Android NDK 프로젝트 구조가 생성되었습니다:

### 📁 Project Structure
```
D:\Project_Jarvis\XREALNativeAR\
├── README.md                   # 프로젝트 개요
├── SETUP_GUIDE.md             # Android Studio & QNN SDK 설치
├── MODEL_CONVERSION_GUIDE.md  # YOLO → DLC 변환
│
├── app/
│   ├── build.gradle           # Android 빌드 설정
│   ├── src/main/
│   │   ├── cpp/              # ✅ C++ 네이티브 코드
│   │   │   ├── CMakeLists.txt
│   │   │   ├── qnn_runtime.cpp/h      # QNN NPU 래퍼
│   │   │   ├── yolo_detector.cpp/h    # YOLO 감지 로직
│   │   │   ├── xreal_camera.cpp/h     # XREAL 카메라
│   │   │   └── native_bridge.cpp      # JNI 브릿지
│   │   │
│   │   ├── kotlin/           # ✅ Kotlin UI
│   │   │   └── com/xreal/nativear/
│   │   │       ├── MainActivity.kt
│   │   │       └── Detection.kt
│   │   │
│   │   ├── AndroidManifest.xml  # ✅ 권한 & USB 설정
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   └── xml/device_filter.xml
│   │   └── assets/
│   │       └── (yolov5s.dlc 여기에 넣기)
│   │
│   └── libs/
│       └── (QNN .so 파일들 여기에 넣기)
│
├── build.gradle            # ✅ 루트 빌드 설정
├── settings.gradle         # ✅ 프로젝트 설정
└── gradle.properties       # ✅ Gradle 속성
```

---

## 🎯 Next Steps: SDK 설치

### Step 1: Android Studio 설치 (필수)
`SETUP_GUIDE.md` 파일을 열어서 따라하세요:

1. Android Studio Ladybug 다운로드 & 설치
2. SDK Manager에서 설치:
   - NDK (Side by side) v26+
   - CMake 3.22.1+
   - Build Tools 34

**예상 시간**: 30분

---

### Step 2: Qualcomm QNN SDK 다운로드 (필수)

#### Option A: QPM 사용 (권장)
1. Qualcomm 계정 생성: https://qpm.qualcomm.com/
2. QPM 다운로드 & 설치
3. QNN SDK v2.28 검색 & 다운로드
4. `D:\QualcommSDK\qnn-v2.28` 에 압축 해제

#### Option B: 수동 다운로드
1. https://developer.qualcomm.com/software/qualcomm-ai-engine-direct-sdk
2. "Download" 클릭 (계정 필요)
3. Windows 버전 다운로드

**예상 시간**: 20분 (다운로드 속도에 따라)

---

### Step 3: QNN Libraries 복사

QNN SDK 설치 후:

```powershell
# QNN Android 라이브러리를 프로젝트로 복사
cd D:\Project_Jarvis\XREALNativeAR
cp D:\QualcommSDK\qnn-v2.28\lib\aarch64-android\*.so libs\
```

필요한 파일:
- `libQnnHtp.so` (Hexagon NPU 런타임)
- `libQnnSystem.so` (시스템 라이브러리)
- `libQnnHtpPrepare.so` (선택)

---

### Step 4: YOLO 모델 변환

`MODEL_CONVERSION_GUIDE.md` 파일을 따라:

```powershell
# 1. YOLOv5 ONNX 모델 준비 (Unity에서 사용한 것)
# 2. QNN Converter로 DLC 변환
# 3. app/src/main/assets/yolov5s.dlc 로 복사
```

---

### Step 5: Android Studio에서 프로젝트 열기

```
1. Android Studio 실행
2. "Open an Existing Project" 클릭
3. D:\Project_Jarvis\XREALNativeAR 폴더 선택
4. Gradle sync 완료 대기
```

---

### Step 6: CMakeLists.txt 경로 수정

`app/src/main/cpp/CMakeLists.txt` 열어서:

```cmake
# 이 줄을 실제 QNN SDK 경로로 수정
set(QNN_SDK_ROOT "D:/QualcommSDK/qnn-v2.28" CACHE PATH "Path to QNN SDK")
```

---

### Step 7: QNN 코드 구현

현재 C++ 코드는 **placeholder**입니다. QNN SDK 설치 후:

1. `qnn_runtime.cpp`에 실제 QNN API 호출 추가:
   ```cpp
   #include "QnnInterface.h"
   #include "QnnTypes.h"
   #include "HTP/QnnHtpDevice.h"
   ```

2. QNN SDK의 `examples/` 폴더 참고

---

### Step 8: 빌드 & 테스트

```
1. Android Studio에서 Build > Make Project
2. 에러 없으면 갤럭시 폴드4 연결
3. Run 버튼 클릭
```

---

## ⚠️ 현재 상태

✅ **완료된 것**:
- Android 프로젝트 구조 생성
- C++ 스켈레톤 코드 작성
- Kotlin UI 구현
- Gradle 빌드 설정

⏳ **사용자가 해야 할 것**:
- [ ] Android Studio 설치
- [ ] Qualcomm QNN SDK 다운로드
- [ ] QNN 라이브러리 복사
- [ ] YOLO 모델 DLC 변환
- [ ] QNN API 실제 구현 (C++)

🔜 **다음 단계** (SDK 설치 후):
- [ ] QNN 런타임 코드 완성
- [ ] XREAL Camera2 통합
- [ ] AR 렌더링 구현
- [ ] 갤럭시 폴드4에서 테스트

---

## 🆘 문제 해결

### "NDK not found" 에러
```
Tools > SDK Manager > SDK Tools 탭
✅ NDK (Side by side) 체크 > Apply
```

### "CMake not found" 에러
```
Tools > SDK Manager > SDK Tools 탭
✅ CMake 체크 > Apply
```

### QNN SDK 어디서 받나요?
```
https://qpm.qualcomm.com/
계정 생성 → QPM 설치 → QNN SDK 검색
```

### Unity 프로젝트는 어떻게 하나요?
```
Unity 프로젝트는 그대로 두세요.
이것은 별도의 네이티브 프로젝트입니다.
성능 비교 후 결정하면 됩니다.
```

---

## 🎯 목표 성능

| 항목 | Unity (GPU) | Native (NPU) | 개선 |
|------|-------------|--------------|------|
| 추론 시간 | 100-200ms | **10-30ms** | 10배 빠름 |
| 배터리/시간 | 30% | **3-5%** | 90% 절약 |
| 발열 | 높음 | **낮음** | 쿨링 |

---

## 📞 다음에 할 일

SDKを설치하고 준비되면 알려주세요. 그 다음으로:

1. **QNN 실제 코드 작성** - SDK 문서 기반
2. **모델 변환 도움** - ONNX → DLC
3. **Camera2 통합** - XREAL 카메라 연결
4. **테스트 & 디버깅** - 실제 기기에서

화이팅! 🚀
