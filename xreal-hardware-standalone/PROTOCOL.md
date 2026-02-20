# Nreal Light — Complete Hardware Protocol Reference

> **⚠️ 이 문서를 반드시 먼저 읽고 코드를 수정하세요.**
> 
> 이 프로토콜은 ar-drivers-rs 리버스 엔지니어링 + 실기기 테스트로 검증된 것입니다.
> AI가 "일반적인 USB/UVC 지식"으로 다시 작성하면 작동하지 않습니다.

---

## 1. 디바이스 구조 — 두 개의 USB 장치

Nreal Light는 USB-C로 **2개의 독립 USB 장치**를 노출합니다:

| 장치 | VID | PID | 역할 | 파일 |
|------|-----|-----|------|------|
| **STM32 MCU** | `0x0486` | `0x573C` | 디스플레이, 버튼, 하트비트 | `MCUManager.kt` |
| **OV580** | `0x05A9` | `0x0680` | 스테레오 카메라 + IMU | `OV580Manager.kt` |
| Realtek Audio | `0x0BDA` | `0x4B77` | 오디오 (미사용) | — |

> [!CAUTION]
> VID `0x0486`은 MCU, VID `0x05A9`는 OV580입니다.
> **두 장치 모두 동시에 열어야** 완전한 AR 기능이 작동합니다.

---

## 2. 소프트웨어 아키텍처

```
XRealHardwareManager.kt  (Facade, ~160줄)
├── USBDeviceRouter.kt    (USB 권한/발견, ~200줄)
├── OV580Manager.kt       (IMU + SLAM 카메라, ~220줄)
│   └── OV580SlamCamera.kt (UVC 스테레오 캡처, ~220줄)
├── MCUManager.kt         (디스플레이/하트비트, ~110줄)
├── NrealDeviceType.kt    (VID/PID enum, ~15줄)
└── MadgwickAHRS.kt       (센서 퓨전 필터)
```

### 활성화 순서 (검증됨)
```
1. USBDeviceRouter.scanAndOpen() — USB 디바이스 스캔
2. OV580 먼저 열기 (직접 open 또는 권한 요청)
3. MCU 그 다음 열기
4. OV580Manager.activateIMU() — HID 인터페이스 활성화
5. MCUManager.activate() — 매직 페이로드 전송
6. onReady() 콜백 — 동기적으로 호출
```

---

## 3. MCU 프로토콜 (VID=0x0486)

### 인터페이스
- Interface 0: HID (Class 3)
- IN Endpoint: `0x81` (Interrupt, 64 bytes)
- OUT Endpoint: `0x01` (Interrupt, 64 bytes)

### 활성화 페이로드
```
0xAA, 0xC5, 0xD1, 0x21, 0x42, 0x04, 0x00, 0x19, 0x01
```

> [!CAUTION]
> **반드시 `bulkTransfer()`로 전송해야 합니다.**
> `controlTransfer()`는 에러 없이 조용히 실패합니다.

```kotlin
// ✅ 올바른 방법
connection.bulkTransfer(endpointOut, magicPayload, magicPayload.size, 200)

// ❌ 잘못된 방법 — 조용히 실패
connection.controlTransfer(0x21, 0x09, 0x0300, 0, magicPayload, ...)
```

### 데이터 읽기
- **동기** `bulkTransfer()` 루프 사용 (별도 스레드)
- `UsbRequest` 비동기 방식은 **작동하지 않음**
- 패킷은 ASCII 텍스트: `\x02:{type}:{cmd}:{data}:{timestamp:>8x}:{crc:>8x}:\x03`

---

## 4. OV580 프로토콜 (VID=0x05A9) — IMU

### 인터페이스 구조
```
Interface 0: Class 14 (Video Control)  — 제어용
Interface 1: Class 14 (Video Streaming) — SLAM 카메라 (별도 섹션 참조)
Interface 2: Class 3  (HID)            — IMU 데이터
  └── IN Endpoint: 0x89 (MaxPacket 128)
```

### IMU 활성화 — HID SET_REPORT

```kotlin
// 1) Interface 2 (Class=3, HID) 클레임
connection.claimInterface(hidInterface, true)

// 2) 7바이트 IMU 활성화 명령 전송
val cmd = byteArrayOf(0x02, 0x19, 0x01, 0x00, 0x00, 0x00, 0x00)
connection.controlTransfer(
    0x21,   // bmRequestType: host→device, class, interface
    0x09,   // bRequest: SET_REPORT
    0x0302, // wValue: Report Type=Feature(3), Report ID=2
    hidInterface.id,
    cmd, cmd.size, 500
)
```

> [!IMPORTANT]
> MCU와 달리, OV580 IMU는 **`controlTransfer()`**로 활성화합니다 (SET_REPORT).
> 데이터 읽기는 MCU와 동일하게 **`bulkTransfer()`** 루프.

### IMU 패킷 포맷

- Endpoint `0x89`에서 벌크 읽기
- `buf[0] == 0x01`: IMU 이벤트 패킷
- `buf[0] == 0x02`: 커맨드 응답 패킷

**IMU 이벤트 패킷 레이아웃 (buf[0] == 0x01, 100+ bytes):**

```
출처: ar-drivers-rs parse_report()

Offset 44: Gyroscope
  44-51: timestamp (u64 LE, microseconds)
  52-55: multiplier (u32 LE)
  56-59: divisor   (u32 LE)
  60-63: X (i32 LE)
  64-67: Y (i32 LE)
  68-71: Z (i32 LE)

Offset 72: Accelerometer
  72-79: timestamp (u64 LE, microseconds)
  80-83: multiplier (u32 LE)
  84-87: divisor   (u32 LE)
  88-91: X (i32 LE)
  92-95: Y (i32 LE)
  96-99: Z (i32 LE)
```

### 물리값 변환

```kotlin
// 스케일링
val gScale = multiplier / divisor  // gyro
val aScale = multiplier / divisor  // accel

// 라디안 변환 (gyro)
val gx =  gyroX * gScale * PI / 180f
val gy = -(gyroY * gScale * PI / 180f)  // Y축 반전!
val gz = -(gyroZ * gScale * PI / 180f)  // Z축 반전!

// m/s² 변환 (accel)
val ax =  accX * aScale * 9.81f
val ay = -(accY * aScale * 9.81f)       // Y축 반전!
val az = -(accZ * aScale * 9.81f)       // Z축 반전!
```

> [!WARNING]
> **Y축과 Z축은 반전(negate)이 필요합니다** (ar-drivers-rs 기준).
> 이걸 빠뜨리면 AHRS 쿼터니언이 뒤집혀서 이상한 방향을 출력합니다.

### 센서 퓨전 (Madgwick AHRS)
- `beta = 0.05` (낮을수록 부드러움, drift 위험)
- `sampleFreq = 1000f` (OV580 IMU는 ~1kHz)
- 출력: 쿼터니언 `[q0, q1, q2, q3]` → `[w, x, y, z]`
- 콜백은 `onOrientationUpdate(qx=q1, qy=q2, qz=q3, qw=q0)` 순서

---

## 5. OV580 프로토콜 — SLAM 스테레오 카메라

### 인터페이스
```
Interface 1: Class 14 (Video Streaming)
  └── IN Endpoint: 0x81 (Bulk, MaxPacket 512)
```

### UVC 스트리밍 활성화 — VS_COMMIT_CONTROL

```kotlin
// 34바이트 UVC 커밋 패킷 (ar-drivers-rs ENABLE_STREAMING_PACKET)
val commitPacket = byteArrayOf(
    0x01, 0x00,                         // bmHint: dwFrameInterval
    0x01,                               // bFormatIndex: 1
    0x01,                               // bFrameIndex: 1
    0x15, 0x16, 0x05, 0x00,             // dwFrameInterval: 333333 (30fps)
    0x00, 0x00,                         // wKeyFrameRate
    0x00, 0x00,                         // wPFrameRate
    0x00, 0x00,                         // wCompQuality
    0x00, 0x00,                         // wCompWindowSize
    0x00, 0x00,                         // wDelay
    0x00, 0x60, 0x09, 0x00,             // dwMaxVideoFrameSize: 0x96000 = 614400
    0x00, 0x00, 0x00, 0x00,             // dwMaxPayloadTransferSize
    0x00, 0x00, 0x00, 0x00,             // dwClockFrequency
    0x00,                               // bmFramingInfo
    0x00,                               // bPreferredVersion
    0x00,                               // bMinVersion
    0x00                                // bMaxVersion
)

// VS_COMMIT_CONTROL 전송
val result = connection.controlTransfer(
    0x21,  // bmRequestType: host→device, class, interface
    0x01,  // bRequest: SET_CUR
    0x0200,// wValue: VS_COMMIT_CONTROL
    1,     // wIndex: Interface 1 (Video Streaming)
    commitPacket, commitPacket.size, 1000
)
// result는 반드시 34여야 함 (== commitPacket.size)
```

### 프레임 데이터 읽기

```
프레임 = 615,680 바이트 raw (640 × 480 × 2 카메라)
전송 = 615,908 바이트 (UVC 헤더 포함)

UVC 헤더: 매 0x8000 (32768) 바이트마다 12바이트 헤더

헤더 구조:
  byte 0: header length (0x0C = 12)
  byte 1: flags
    bit 0 (0x01): parity (프레임마다 토글)
    bit 1 (0x02): end of frame marker
```

### UVC 헤더 스트리핑 로직

```kotlin
val CHUNK_SIZE = 0x8000        // 32768
val UVC_HEADER_SIZE = 12
val DATA_PER_CHUNK = CHUNK_SIZE - UVC_HEADER_SIZE  // 32756

while (remaining > 0) {
    val n = connection.bulkTransfer(endpoint, buf, bufSize, timeout)
    // buf 내에서 매 CHUNK_SIZE마다 앞 12바이트 스킵
    for (offset in 0 until n step CHUNK_SIZE) {
        val dataStart = offset + UVC_HEADER_SIZE
        val dataEnd = minOf(offset + CHUNK_SIZE, n)
        // dataStart..dataEnd 범위가 실제 이미지 데이터
        rawData.append(buf, dataStart, dataEnd - dataStart)
    }
}
```

### 스테레오 디인터리브

```
Raw 프레임: 640 × 960 그레이스케일 (인터리브)
  짝수 행 (0, 2, 4, ...) → 왼쪽 카메라 (640 × 480)
  홀수 행 (1, 3, 5, ...) → 오른쪽 카메라 (640 × 480)
```

```kotlin
val WIDTH = 640
val HEIGHT = 480
val left  = ByteArray(WIDTH * HEIGHT)
val right = ByteArray(WIDTH * HEIGHT)

for (row in 0 until HEIGHT) {
    val srcEven = (row * 2) * WIDTH      // 짝수 행 → 왼쪽
    val srcOdd  = (row * 2 + 1) * WIDTH  // 홀수 행 → 오른쪽
    val dst = row * WIDTH
    System.arraycopy(rawFrame, srcEven, left, dst, WIDTH)
    System.arraycopy(rawFrame, srcOdd, right, dst, WIDTH)
}
```

### 검증된 성능
- **28.1 FPS** (목표 30fps)
- **615,908 bytes** 전송, **615,680 bytes** raw
- IMU 1kHz와 동시 작동 확인
- Galaxy Fold 4 (Snapdragon 8+ Gen 1)에서 테스트 완료

---

## 6. Android USB 주의사항

### 권한 (Android 14+, targetSdk 34)
```kotlin
// 명시적 Intent + FLAG_MUTABLE 필수
val intent = Intent(ACTION_USB_PERMISSION).apply {
    setPackage(context.packageName)
}
PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)

// BroadcastReceiver: Android 13+에서 RECEIVER_EXPORTED 필수
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
}
```

### USB 디바이스 열기 전략
```
1. usbManager.openDevice(device) 직접 시도
2. 성공 → 사용
3. SecurityException → 권한 요청 다이얼로그
4. null → 이미 다른 프로세스가 점유 중
```

### 디버깅
- **무선 ADB logcat은 불안정** — 로그가 자주 누락됨
- `sLog()` 패턴으로 화면에 직접 로그 표시
- 앱 시작 시 버전 번호 확인

---

## 7. 절대 하면 안 되는 것 ❌

| 잘못된 접근 | 왜 안 되는지 | 올바른 방법 |
|------------|------------|-----------|
| MCU `controlTransfer`로 매직 전송 | 조용히 실패 | `bulkTransfer` |
| `UsbRequest` 비동기 폴링 | 불안정 | 동기 `bulkTransfer` 루프 |
| 축 반전 빠뜨리기 (Y, Z) | 쿼터니언 뒤집힘 | Y, Z negate |
| `FLAG_IMMUTABLE` 또는 암시적 Intent | Android 14 크래시 | 명시적 Intent + `FLAG_MUTABLE` |
| OV580와 MCU를 하나의 장치로 취급 | 두 개의 별도 USB 장치 | VID로 구분 |
| UVC 헤더 없이 raw 데이터 사용 | 이미지 깨짐 | 매 32768바이트마다 12바이트 스킵 |
| 직접 카메라 프레임 사용 | 좌우 인터리브 | 짝수행=좌, 홀수행=우 디인터리브 |

---

## 8. 참조 소스

| 소스 | URL | 내용 |
|------|-----|------|
| ar-drivers-rs | https://gitlab.com/AirVR/ar-drivers-rs | OV580 프로토콜 리버스 엔지니어링 (Rust) |
| enricoros/android-nreal | https://github.com/enricoros/android-nreal | MCU 매직 페이로드 발견 |
| 이 프로젝트 실기기 테스트 | Galaxy Fold 4 + Nreal Light | 모든 바이트 오프셋 검증 |

---

> **마지막 주의**: 이 문서의 바이트 오프셋, 축 반전, UVC 헤더 크기는 모두
> 실기기에서 28 FPS 스테레오 + 1kHz IMU 동시 작동으로 **검증 완료**된 값입니다.
> "개선"을 위해 이 값들을 변경하지 마세요.
