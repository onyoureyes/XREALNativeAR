# YOLO to Qualcomm DLC Model Conversion

## Prerequisites
- Qualcomm QNN SDK installed at: `D:\QualcommSDK\qnn-v2.28`
- Python 3.8+ with pip
- Original YOLO ONNX model

## Step 1: Setup Python Environment

```powershell
# Create virtual environment
cd D:\Project_Jarvis\XREALNativeAR
python -m venv venv

# Activate
.\venv\Scripts\Activate.ps1

# Install dependencies
pip install numpy onnx onnxruntime
```

## Step 2: Prepare YOLO Model

### Download YOLOv5 ONNX (if you don't have it)
```powershell
# Clone YOLOv5 repo
cd D:\Project_Jarvis\XREALNativeAR
git clone https://github.com/ultralytics/yolov5.git
cd yolov5

# Install requirements
pip install -r requirements.txt

# Export to ONNX (300x300 for efficiency)
python export.py --weights yolov5s.pt --include onnx --imgsz 300 --simplify
```

This creates: `yolov5s.onnx`

### Or use existing ONNX model
If you already have the YOLO ONNX from Unity project, copy it:
```powershell
cp F:\UnityProject\*.onnx D:\Project_Jarvis\XREALNativeAR\yolov5s.onnx
```

## Step 3: Convert ONNX to DLC (Qualcomm Format)

### Method A: Using QNN Converter (Recommended)

```powershell
# Set environment
$env:Path += ";D:\QualcommSDK\qnn-v2.28\bin\x86_64-windows-msvc"
$env:PYTHONPATH = "D:\QualcommSDK\qnn-v2.28\lib\python"

# Convert to DLC
qnn-onnx-converter `
  --input_network yolov5s.onnx `
  --output_path yolov5s.dlc `
  --input_dim input "1,3,300,300" `
  --out_node output
```

### Method B: Using SNPE (Alternative if QNN fails)

```powershell
# If you have SNPE SDK instead
snpe-onnx-to-dlc `
  --input_network yolov5s.onnx `
  --output_path yolov5s.dlc
```

## Step 4: Quantize for NPU (INT8)

INT8 quantization makes the model 4x faster on NPU!

```powershell
# Generate sample input data first
python - <<EOF
import numpy as np
# Create random 300x300 RGB image
data = np.random.rand(1, 3, 300, 300).astype(np.float32)
data.tofile('sample_input.raw')
EOF

# Quantize model
qnn-dlc-quantize `
  --input_dlc yolov5s.dlc `
  --output_dlc yolov5s_quantized.dlc `
  --input_list input_list.txt
```

Create `input_list.txt`:
```
sample_input.raw
```

## Step 5: Verify Conversion

### Test on PC (x86 emulation)
```powershell
# Run inference test
qnn-net-run `
  --model yolov5s_quantized.dlc `
  --input_list input_list.txt `
  --output_dir results\
```

If successful, you'll see:
```
Successfully executed model!
Output saved to: results/output.raw
```

### Check model info
```powershell
qnn-dlc-info yolov5s_quantized.dlc
```

Expected output:
```
Model: yolov5s_quantized.dlc
Input layers:
  - input: [1, 3, 300, 300] (float32)
Output layers:
  - output: [1, 25200, 85] (float32)
Quantized: Yes (INT8)
Target: Hexagon
```

## Step 6: Deploy to Android Project

```powershell
# Copy quantized model to Android assets
cp yolov5s_quantized.dlc app\src\main\assets\yolov5s.dlc
```

---

## Troubleshooting

### Error: "qnn-onnx-converter not found"
```powershell
# Check QNN SDK path
ls D:\QualcommSDK\qnn-v2.28\bin
# Make sure PATH is set correctly
```

### Error: "Unsupported ONNX operator"
Some YOLO operations might not be supported. Solutions:
1. Use `--simplify` when exporting ONNX
2. Use YOLOv5s (smallest, most compatible)
3. Try SNPE SDK instead of QNN

### Model output is wrong
- Verify input preprocessing (RGB vs BGR?)
- Check normalization (0-255 vs 0-1)
- YOLO might need special post-processing

---

## Next Steps

Once you have `yolov5s.dlc`:
1. Copy to Android app assets
2. Write C++ code to load it with QNN Runtime
3. Test on Galaxy Fold 4 NPU

Expected Performance:
- **Before (Unity GPU)**: ~100-200ms per frame
- **After (QNN NPU INT8)**: ~10-30ms per frame
- **Battery**: 70-90% reduction

Let me know when QNN SDK is installed and we'll run the conversion!
