# XREAL Light 카메라 검증 가이드

## 사용자 제공 정보 기반 수정 사항

### 핵심 팩트

1. **카메라 타입**: Fisheye (어안 렌즈) ⚠️
   - Pinhole 가정 코드는 작동 안 함
   - 왜곡 보정 필수

2. **USB IDs**:
   - Vendor: `0x0bda` (Realtek)
   - Product: `0x0580` (OV580)
   
3. **출력 포맷**: Side-by-Side YUV/Grayscale

4. **IMU**: HID 인터페이스 별도

---

## Phase 1: 카메라 Raw Data 검증

### 목표
**"1280x480 흑백 영상이 폰에 들어오는가?"**

### 검증 코드 (이미 작성됨)

파일: `stereo_camera.cpp`

핵심 로직:
```cpp
// 1. UVC 디바이스 찾기 (0x0bda:0x0580)
uvc_find_device(ctx, &dev, 0x0bda, 0x0580, nullptr);

// 2. 1280x480 @ 30fps 요청
uvc_get_stream_ctrl_format_size(devh, ctrl,
    UVC_FRAME_FORMAT_GRAY8, 1280, 480, 30);

// 3. Side-by-Side 분리
left = frame(Rect(0, 0, 640, 480));
right = frame(Rect(640, 0, 640, 480));
```

### 테스트 절차

1. **XREAL Light 연결**
   ```bash
   adb shell lsusb
   # 출력에서 0bda:0580 확인
   ```

2. **앱 실행**
   ```kotlin
   if (initStereoCamera()) {
       Log.i(TAG, "✅ Camera OK!")
   }
   ```

3. **예상 로그**
   ```
   I/StereoCamera: Device found: Realtek OV580
   I/StereoCamera: ✅ Streaming started!
   I/StereoCamera: Resolution: 1280x480 @ 30 FPS
   ```

4. **실패 시**
   - Product ID 다를 수 있음 → `lsusb` 출력 확인
   - USB 권한 필요 → AndroidManifest.xml 수정

---

## Phase 2: Fisheye 왜곡 보정

**검증 완료 후 추가 필요:**

### OpenCV Fisheye 모듈 사용

```cpp
#include <opencv2/calib3d.hpp>

// 캘리브레이션 파라미터 (XREAL에서 추출 필요)
cv::Mat K;  // 내부 파라미터
cv::Mat D;  // 왜곡 계수 (k1, k2, k3, k4)

// 왜곡 보정
cv::Mat undistorted;
cv::fisheye::undistortImage(distorted, undistorted, K, D);
```

### 캘리브레이션 데이터 획득 방법

**옵션 A**: XREAL 펌웨어에서 추출
```cpp
// ar_drivers 라이브러리 참조
// OV580 HID 엔드포인트에서 캘리브레이션 파일 요청
```

**옵션 B**: 수동 캘리브레이션
```bash
# 체커보드 패턴 사용
opencv_interactive-calibration \
  --camera-type fisheye \
  --frames 20 \
  --board-width 9 \
  --board-height 6
```

---

## Phase 3: IMU 통합

### HID 접근 (hidapi 사용)

```cpp
#include "imu_reader.h"

IMUReader imu;
imu.initialize();

IMUData data;
while (imu.readSample(data)) {
    printf("Accel: %.2f, %.2f, %.2f\n",
           data.accel_x, data.accel_y, data.accel_z);
    printf("Gyro: %.2f, %.2f, %.2f\n",
           data.gyro_x, data.gyro_y, data.gyro_z);
}
```

---

## Phase 4: ORB-SLAM3 Fisheye 모드

### 설정 파일 (xreal_fisheye.yaml)

```yaml
%YAML:1.0

Camera.type: "KannalaBrandt8"  # Fisheye 모델

# 내부 파라미터 (캘리브레이션 후 업데이트)
Camera.fx: 285.0
Camera.fy: 285.0
Camera.cx: 320.0
Camera.cy: 240.0

# Fisheye 왜곡 계수
Camera.k1: 0.1
Camera.k2: 0.05
Camera.k3: -0.02
Camera.k4: 0.01

# Stereo
Camera.bf: 48.0  # baseline * fx
ThDepth: 40.0
```

### SLAM 초기화

```cpp
ORB_SLAM3::System SLAM(
    "ORBvoc.txt",
    "xreal_fisheye.yaml",
    ORB_SLAM3::System::STEREO,  // Stereo mode
    true,                        // Use viewer
    0,                           // init frame ID
    "",                          // no sequence
    true                         // Use IMU
);
```

---

## 타임라인

| Phase | 작업 | 예상 시간 |
|-------|------|----------|
| 1 | **카메라 Raw 검증** | 1일 |
| 2 | Fisheye 왜곡 보정 | 1-2일 |
| 3 | IMU 통합 | 1일 |
| 4 | ORB-SLAM3 Fisheye | 2-3일 |

**Total: ~5-7일**

---

## 리스크 완화 전략

### 1. 캘리브레이션 실패
**대응**: OpenCV 샘플 코드로 수동 캘리브레이션 진행

### 2. IMU 동기화 어려움
**대응**: 초기에는 Visual-only SLAM으로 시작 (IMU 나중에 추가)

### 3. 성능 부족
**대응**: ORB 특징점 수 조절, 매 프레임이 아닌 키프레임만 처리

---

## 다음 단계

**즉시 실행**: 
1. ✅ USB Product ID 수정 (0x0580)
2. ✅ IMU Reader 헤더 생성
3. ⏳ Fisheye 왜곡 보정 코드 추가
4. ⏳ 실제 기기 테스트

**1단계만 성공하면 나머지는 시간 문제입니다.**
