# XREALNativeAR - NPU-Powered Object Detection

Native Android AR application using Qualcomm NPU acceleration and XREAL Light open-source drivers.

## Project Structure

```
XREALNativeAR/
├── SETUP_GUIDE.md              # Android Studio & QNN SDK setup
├── MODEL_CONVERSION_GUIDE.md   # YOLO ONNX → DLC conversion
├── app/                        # Android app (to be created)
│   ├── src/main/
│   │   ├── cpp/               # C++ native code
│   │   ├── kotlin/            # Kotlin UI
│   │   └── assets/            # yolov5s.dlc model
│   └── build.gradle
└── libs/                      # QNN native libraries
```

## Quick Start

### 1. Install Prerequisites
Follow `SETUP_GUIDE.md`:
- Install Android Studio + NDK
- Download Qualcomm QNN SDK

### 2. Convert YOLO Model
Follow `MODEL_CONVERSION_GUIDE.md`:
- Export YOLO to ONNX
- Convert to Qualcomm DLC format
- Quantize for NPU (INT8)

### 3. Create Android App
(Next phase - will be documented)

## Technology Stack

- **Language**: C++ (native), Kotlin (UI)
- **Inference**: Qualcomm QNN SDK (Hexagon NPU)
- **AR Display**: XREAL Light (via Monado or USB direct)
- **Rendering**: OpenGL ES 3.0

## Performance Goals

| Metric | Unity + GPU | Native + NPU | Improvement |
|--------|-------------|--------------|-------------|
| Inference Time | 100-200ms | 10-30ms | **10x faster** |
| Battery/hour | 30% | 3-5% | **90% less** |
| Heat | High | Minimal | **Cool operation** |

## Status

- [x] Planning complete
- [/] Environment setup in progress
- [ ] Model conversion pending QNN SDK
- [ ] Android app development pending
- [ ] XREAL driver integration pending
- [ ] Testing on Galaxy Fold 4 pending

## License

Personal project - open source components are credited in implementation_plan.md
