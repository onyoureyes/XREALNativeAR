# Plan B: NRSDK Wrapper Implementation Guide

## 빠른 검증 결과

### 발견한 NRSDK AAR 파일

```
✅ 핵심 파일 복사 완료:
├─ nr_api.aar        (Core API)
├─ nr_common.aar     (Common utilities)
└─ nr_loader.aar     (Library loader)

📦 전체 31개 AAR 발견 (필요시 추가 가능)
```

---

## 구현 상태

### ✅ 완료
1. NRSDK AAR 파일 위치 확인
2. 핵심 AAR 복사 (`app/libs/`)
3. Java 래퍼 클래스 생성 (`NRSDKWrapper.kt`)
4. JNI 브릿지 C++ 구현 (`nrsdk_bridge.cpp/h`)

### ⏳ 진행 중
1. Gradle 설정 (AAR 링크)
2. 리플렉션으로 NRSDK 클래스 접근
3. 실제 기기 테스트

---

## 사용 방법

### Kotlin (Java) 측

```kotlin
// MainActivity.kt
val nrsdkWrapper = NRSDKWrapper(this)

if (nrsdkWrapper.initialize()) {
    Log.i(TAG, "✅ NRSDK Plan B initialized!")
    
    // 매 프레임
    Thread {
        while (running) {
            nrsdkWrapper.update()
            
            // 6DoF Pose 가져오기
            val pose = nrsdkWrapper.getHeadPose()
            if (pose != null) {
                // Native로 전달
                updateHeadPoseNative(pose)
            }
        }
    }.start()
} else {
    Log.e(TAG, "❌ NRSDK not available, use Plan A")
}
```

### Native (C++) 측

```cpp
#include "nrsdk_bridge.h"

void render() {
    if (NRSDKBridge::isPoseValid()) {
        // View matrix 가져오기
        glm::mat4 viewMatrix = NRSDKBridge::getViewMatrix();
        
        // OpenGL 렌더링
        glUniformMatrix4fv(viewMatrixLoc, 1, GL_FALSE, 
                          glm::value_ptr(viewMatrix));
        
        // AR 컨텐츠 그리기
        drawARContent();
    }
}
```

---

## 다음 단계

### Phase 1: Gradle 설정 (즉시)
```gradle
// app/build.gradle
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.aar'])
    // 또는
    implementation files('libs/nr_api.aar')
    implementation files('libs/nr_common.aar')
    implementation files('libs/nr_loader.aar')
}
```

### Phase 2: 리플렉션 테스트 (1일)
```kotlin
// NRSDK 클래스 찾기
val classes = listOf(
    "ai.nreal.sdk.NRSession",
    "com.nreal.sdk.NRSession",
    "com.nreal.nrapi.NRSession"
)

for (className in classes) {
    try {
        val cls = Class.forName(className)
        Log.i(TAG, "✅ Found: $className")
        // 메서드 나열
        cls.methods.forEach {
            Log.d(TAG, "  - ${it.name}")
        }
    } catch (e: ClassNotFoundException) {
        Log.d(TAG, "❌ Not found: $className")
    }
}
```

### Phase 3: AAR 내부 분석 (필요시)
```bash
# AAR은 사실 ZIP 파일
unzip nr_api.aar -d nr_api_extracted

# 구조 확인
tree nr_api_extracted
# classes.jar → 자바 클래스
# jni/ → 네이티브 라이브러리 (.so)
```

---

## 예상 문제 및 해결

### 문제 1: NRSDK 클래스 못 찾음
**증상**: `ClassNotFoundException`

**해결**:
1. AAR의 `classes.jar` 확인
2. Gradle sync 실행
3. 패키지 이름 변경 (ai.nreal.* → com.nreal.*)

### 문제 2: 네이티브 라이브러리 로드 실패
**증상**: `UnsatisfiedLinkError`

**해결**:
1. AAR 내 `.so` 파일 확인
2. `jniLibs/arm64-v8a/` 로 복사
3. `System.loadLibrary()` 이름 확인

### 문제 3: Unity 의존성
**증상**: 특정 Unity 클래스 요구

**해결**:
1. 최소 AAR만 사용 (nr_api, nr_common)
2. Unity-specific AAR 제외
3. 직접 JNI 호출로 대체

---

## Plan A vs Plan B 비교 (Week 1 말 예상)

| 항목 | Plan A (UVC+SLAM) | Plan B (NRSDK) |
|------|-------------------|----------------|
| **구현 난이도** | ⭐️⭐️⭐️⭐️⭐️ | ⭐️⭐️⭐️ |
| **6DoF 정확도** | 수동 캘리브레이션 필요 | NRSDK 내장 |
| **개발 속도** | 2주+ | 3-5일 |
| **제어권** | 100% | 70% (블랙박스) |
| **문서** | ORB-SLAM3 풍부 | NRSDK 부족 |

---

## GO/NO-GO 기준 (Week 1 End)

### Plan B SUCCESS 조건:
- ✅ AAR 링크 성공
- ✅ NRSession 인스턴스 생성
- ✅ HeadPose 4x4 행렬 획득
- ✅ 30 FPS 이상

### Plan B FAIL 조건:
- ❌ Unity 하드 의존성 발견
- ❌ 핵심 클래스 접근 불가
- ❌ 네이티브 라이브러리 호환 문제

**FAIL 시**: Plan A (UVC+ORB-SLAM3) 집중

---

## 현재 상태 (Step 1298)

```
✅ AAR 파일 발견 및 복사
✅ Java 래퍼 뼈대 완성
✅ JNI 브릿지 구현
⏳ Gradle 설정 대기
⏳ 리플렉션 테스트 대기
```

**다음**: Gradle 설정 업데이트 → 빌드 테스트
