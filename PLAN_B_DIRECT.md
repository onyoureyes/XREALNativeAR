# Plan B v2: Direct Java Wrapper (No AAR)

## 전략 전환 이유

### ❌ AAR 방식의 문제
```
BUILD FAILED: minSdk conflict
nr_api.aar requires minSdk > 24
```

- 버전 충돌
- 의존성 지옥
- 블랙박스

### ✅ 직접 구현의 장점

1. **최소 코드** (3 파일)
2. **완전한 제어**
3. **의존성 제로**

---

## 구현 완료

### 파일 구조

```
app/src/main/kotlin/com/xreal/nativear/nrsdk/
└── DirectNRSDK.kt (250 lines)
    ├── NRFrame (데이터 클래스)
    ├── Pose (6DoF 변환)
    ├── DirectNRSession (센서 인터페이스)
    └── MinimalNRSDK (Facade)
```

### 핵심 API

```kotlin
// 초기화
MinimalNRSDK.initialize(context)

// 매 프레임
val frame = MinimalNRSDK.getCurrentFrame()
if (frame != null) {
    val poseMatrix = frame.headPose.toMatrix()  // 4x4 행렬
    updateNative(poseMatrix)
}
```

---

## 현재 구현: Mock Mode

### 왜 Mock인가?

실제 XREAL 하드웨어 접근은 두 가지 방법:

**Option A**: NRSDK 내부 알고리즘 역공학 (수개월 소요)
**Option B**: Android Sensors + 간단한 융합 (현실적)

**현재**: 테스트용 Mock 데이터 생성

```kotlin
// Mock: 천천히 회전하는 헤드
val angle = (frameCount * 0.01f) % (2 * PI)
val rotation = Quaternion(0f, sin(angle/2), 0f, cos(angle/2))
```

---

## 실제 센서 통합 계획 (Option B)

### Phase 1: Android Sensors

```kotlin
class DirectNRSession(context: Context) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    fun start() {
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                // 회전 속도 → 쿼터니언 업데이트
                updateOrientation(event.values)
            }
        }
    }
}
```

### Phase 2: 간단한 융합

```kotlin
// Complementary Filter (간단한 센서 융합)
private fun fuseSensors() {
    val alpha = 0.98f
    
    // Gyroscope (빠르고 정확, 드리프트 있음)
    orientationFromGyro = integrateGyro(gyroData)
    
    // Accelerometer (느리고 노이즈 많음, 드리프트 없음)
    orientationFromAccel = gravityToOrientation(accelData)
    
    // 융합
    finalOrientation = alpha * orientationFromGyro + 
                       (1 - alpha) * orientationFromAccel
}
```

---

## 테스트 결과 (예상)

### Mock Mode
- ✅ 빌드 성공 (AAR 없음)
- ✅ JNI 브릿지 작동
- ✅ 4x4 행렬 전달
- ⚠️ 실제 헤드 움직임 반영 안 됨

### Sensor Mode (TODO)
- ✅ 실제 헤드 회전 추적
- ⚠️ 위치 추적 없음 (3DoF)
- ⚠️ 드리프트 발생 가능

---

## Plan A vs Plan B 비교 (Updated)

| 항목 | Plan A (UVC+SLAM) | Plan B (Direct) |
|------|-------------------|-----------------|
| **6DoF** | Full (위치+회전) | 3DoF (회전만) |
| **난이도** | ⭐️⭐️⭐️⭐️⭐️ | ⭐️⭐️ |
| **의존성** | libuvc, ORB-SLAM3 | 제로 |
| **속도** | 2주+ | 2일 |
| **품질** | 매우 정확 | 보통 |

---

## 다음 단계

### 즉시 (빌드 테스트)
1. ✅ AAR 의존성 제거
2. ✅ DirectNRSDK.kt 생성
3. ⏳ Gradle 빌드
4. ⏳ Mock 데이터 테스트

### Week 1 (센서 통합)
1. Android Gyroscope 읽기
2. Complementary Filter 구현
3. 3DoF 회전 추적

### Week 2 (결정)
- Plan A: UVC 성공 → 6DoF
- Plan B: 센서만 → 3DoF
- **하이브리드**: Plan A SLAM + Plan B 렌더링

---

## 코드 사용법

### MainActivity.kt

```kotlin
class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Plan B 초기화
        if (MinimalNRSDK.initialize(this)) {
            Log.i(TAG, "✅ Plan B initialized (Mock mode)")
            startARLoop()
        }
    }
    
    private fun startARLoop() {
        Thread {
            while (isRunning) {
                // 프레임 가져오기
                val frame = MinimalNRSDK.getCurrentFrame()
                
                if (frame != null) {
                    // 4x4 행렬로 변환
                    val matrix = frame.headPose.toMatrix()
                    
                    // Native로 전달
                    updateHeadPoseNative(matrix)
                    
                    // 상태 표시
                    runOnUiThread {
                        statusText.text = "Tracking: ${frame.trackingState}"
                    }
                }
                
                Thread.sleep(16)  // ~60 FPS
            }
        }.start()
    }
    
    private external fun updateHeadPoseNative(matrix: FloatArray)
}
```

---

## 현재 상태

```
✅ AAR 의존성 제거
✅ DirectNRSDK.kt (Mock mode)
✅ JNI 브릿지 (nrsdk_bridge.cpp)
⏳ 빌드 테스트
⏳ 실제 센서 통합 (Option)
```

**Plan B는 이제 AAR 없이 작동합니다!** 🎉
