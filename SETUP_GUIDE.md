# 🚀 Phase 1: Development Environment Setup

## Step 1: Install Android Studio

### Download Android Studio
1. Go to: https://developer.android.com/studio
2. Download **Android Studio Ladybug** (latest 2024 version)
3. Install with default settings

### First Launch Setup
- Accept licenses
- Choose **Standard** installation
- Wait for SDK downloads (this may take 15-30 minutes)

---

## Step 2: Install Android NDK & Build Tools

### Via SDK Manager (Recommended)
1. Open Android Studio
2. Go to: `Tools > SDK Manager`
3. Click **SDK Tools** tab
4. Check these items:
   - ✅ **NDK (Side by side)** - version 26.x or higher
   - ✅ **CMake** - version 3.22.1 or higher
   - ✅ **Android SDK Build-Tools 34**
   - ✅ **Android Emulator** (optional)
5. Click **Apply** → Wait for download

### Verify Installation
```powershell
# Check NDK path (should show version)
ls "$env:LOCALAPPDATA\Android\Sdk\ndk"

# Check CMake
ls "$env:LOCALAPPDATA\Android\Sdk\cmake"
```

Expected output:
```
Directory: C:\Users\GamerV\AppData\Local\Android\Sdk\ndk
Mode                 LastWriteTime         Length Name
----                 -------------         ------ ----
d-----          26.3.11579264
```

---

## Step 3: Download Qualcomm QNN SDK

### Create Qualcomm Account
1. Go to: https://qpm.qualcomm.com/
2. Click **Sign Up** (free account)
3. Verify email

### Download via QPM (Qualcomm Package Manager)
1. Download QPM installer: https://qpm.qualcomm.com/#/main/tools/details/QPM3
2. Install QPM
3. Login with your Qualcomm ID
4. Search for: **"QNN SDK"**
5. Download **QNN SDK v2.28** or latest
6. Extract to: `D:\QualcommSDK\qnn-v2.28`

### Alternative: Manual Download
If QPM doesn't work:
1. Go to: https://developer.qualcomm.com/software/qualcomm-ai-engine-direct-sdk
2. Download **Qualcomm AI Engine Direct SDK**
3. Extract to `D:\QualcommSDK\`

---

## Step 4: Verify QNN SDK

```powershell
# Navigate to QNN SDK
cd D:\QualcommSDK\qnn-v2.28

# Check structure
ls
```

Expected folders:
```
bin/          - QNN converter tools
lib/          - Native libraries (libQnnHtp.so, etc.)
  └── aarch64-android/
include/      - C++ headers
examples/     - Sample code
docs/         - Documentation
```

### Test QNN Converter
```powershell
# Add to PATH temporarily
$env:Path += ";D:\QualcommSDK\qnn-v2.28\bin\x86_64-windows-msvc"

# Check converter
qnn-onnx-converter --version
```

---

## Step 5: Setup Project Structure

```powershell
# Create project directory
cd D:\Project_Jarvis\XREALNativeAR

# Create folders
mkdir app, app\src, app\src\main, app\src\main\cpp, app\src\main\kotlin
mkdir app\src\main\assets
mkdir libs
```

### Copy QNN Libraries
```powershell
# Copy Android ARM64 libraries to project
cp D:\QualcommSDK\qnn-v2.28\lib\aarch64-android\*.so D:\Project_Jarvis\XREALNativeAR\libs\
```

---

## Step 6: Optional - Download Monado Source

### Clone Monado Repository
```powershell
cd D:\Project_Jarvis
git clone https://gitlab.freedesktop.org/monado/monado.git
cd monado
git checkout main
```

### Build for Android (Advanced)
**Note**: Monado Android build is complex. We'll start with a simpler XREAL USB access approach first.

---

## Current Status

✅ Tasks completed in this step:
- Created project folder structure

⏳ Manual tasks for you:
1. Install Android Studio (30 min)
2. Install NDK via SDK Manager (10 min)
3. Create Qualcomm account & download QNN SDK (20 min)

⏭️ Next Phase:
Once you've completed the manual installations, we'll:
- Convert YOLO ONNX → Qualcomm DLC
- Test NPU inference
- Create the Android NDK app structure

---

## Need Help?

### Android Studio won't install?
- Make sure Windows 10/11 with 8GB+ RAM
- Free up 10GB disk space

### QNN SDK download issues?
- Try alternative: SNPE SDK (older but more stable)
- Link: https://developer.qualcomm.com/software/qualcomm-neural-processing-sdk

### Can't find NDK?
```powershell
# NDK should be here:
ls "$env:LOCALAPPDATA\Android\Sdk\ndk"
```

Let me know when Android Studio + QNN SDK are ready!
