# NPU Failed - 디버깅 가이드

## 확인된 사항 ✅

### APK 포함 파일
```
✅ yolov5s.dlc (29MB) - 모델 파일
✅ libQnnHtp.so (2MB) - NPU 드라이버
✅ libQnnSystem.so (272KB) - 시스템
✅ libxreal_native_ar.so - 우리 코드
```

**→ 파일은 모두 정상!**

---

## 가능한 원인

### 1. 기기 호환성 (가능성 낮음)
- Galaxy Fold 4 = Snapdragon 8+ Gen 1 ✅
- QNN 2.31 지원함 ✅

### 2. 런타임 에러 (가능성 높음)

#### A. QNN 버전 미스매치
```
Galaxy Fold 4의 실제 HTP 버전:
- V68, V69, V73, V75, V79 중 하나

APK 포함된 스텁:
✅ 모두 포함됨
```

#### B. 모델 파일 경로 문제
```kotlin
// MainActivity.kt
val modelFile = File(filesDir, "yolov5s.dlc")
// → /data/data/com.xreal.nativear/files/yolov5s.dlc
```

**문제**: 파일 복사 실패 가능성

#### C. 퍼미션 문제
```
/data/data/... 경로 접근 권한
```

---

## 진단 방법

### Option 1: ADB 로그캣 (가장 정확)

```bash
# PC에서
adb logcat | findstr "XREALNativeAR"
adb logcat | findstr "QNN"
```

**확인할 로그**:
```
E/XREALNativeAR: Failed to initialize QNN NPU
E/QnnRuntime: [에러 메시지]
```

### Option 2: 파일 확인

```bash
adb shell run-as com.xreal.nativear ls -lh files/
# yolov5s.dlc 파일 있는지 확인
```

### Option 3: 라이브러리 로드 확인

```bash
adb logcat | findstr "dlopen"
# libQnn 로드 성공/실패 확인
```

---

## 빠른 수정 (추측)

### 가능성 1: 모델 복사 실패

**증상**: `filesDir`에 파일 복사 안 됨

**해결**:
```kotlin
// 복사 확인 추가
if (modelFile.exists()) {
    Log.i(TAG, "✅ Model exists: ${modelFile.length()} bytes")
} else {
    Log.e(TAG, "❌ Model file not created!")
}
```

### 가능성 2: QNN 초기화 순서 문제

**증상**: Backend 초기화 실패

**해결**:
```cpp
// qnn_runtime.cpp
// 1. Backend 먼저
if (!loadBackend()) {
    LOGE("Backend load failed!");  // ← 여기서 멈춤?
}
```

---

## 임시 우회 (테스트용)

### CPU 모드로 전환

```cpp
// qnn_runtime.cpp
// NPU 대신 CPU 사용
QnnBackend_setConfig(QNN_BACKEND_CONFIG_CPU);
```

**FPS**: 1-2 FPS (느림!)  
**목적**: 파이프라인 테스트

---

## 다음 단계

### 즉시 가능
1. **ADB 로그캣 보기** (가장 정확)
   ```
   adb logcat -c  # 로그 클리어
   [앱 실행]
   adb logcat > log.txt  # 로그 저장
   ```

2. **진단용 APK 재빌드**
   - 더 상세한 에러 로그 추가
   - 각 단계별 확인 메시지

### 대안
- **CPU 모드로 빌드** (느리지만 작동)
- **간단한 테스트** (더미 데이터로만)

---

## 로그캣 없이 진단하려면

**증상 패턴**:
1. "NPU Failed" → 초기화 단계 실패
2. 앱 크래시 → 라이브러리 로드 실패
3. 느림 (< 1 FPS) → CPU 폴백 모드

**지금 상황**: "NPU Failed" → `initQNN()` 리턴 false

---

## 필요한 정보

1. **정확한 에러 메시지** (ADB 로그)
2. **앱 크래시 여부**
3. **카메라는 작동하는지**

로그 없이는 추측만 가능합니다!
