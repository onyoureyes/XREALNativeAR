# UVC Stereo Camera Setup Guide

## Prerequisites

### 1. Download libuvc for Android

```bash
# Clone libuvc
git clone https://github.com/libuvc/libuvc.git
cd libuvc

# Build for Android (all ABIs)
mkdir build-android
cd build-android

# For arm64-v8a (Galaxy Fold 4)
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON

make -j8
```

### 2. Copy libuvc to Project

```bash
# Create jniLibs directory
mkdir -p app/src/main/jniLibs/arm64-v8a

# Copy built library
cp build-android/libuvc.so app/src/main/jniLibs/arm64-v8a/

# Copy headers
mkdir -p app/src/main/cpp/libuvc
cp -r libuvc/include/libuvc/* app/src/main/cpp/libuvc/
```

## USB Permissions

### Update AndroidManifest.xml

Add USB host support:

```xml
<manifest>
    <!-- USB Host Feature -->
    <uses-feature android:name="android.hardware.usb.host" android:required="true" />
    
    <!-- USB Permission -->
    <uses-permission android:name="android.permission.USB_PERMISSION" />
    
    <application>
        <!-- USB Device Filter -->
        <activity android:name=".MainActivity">
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </activity>
    </application>
</manifest>
```

### Create res/xml/device_filter.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- XREAL Light OV580 Camera -->
    <usb-device vendor-id="3034" product-id="22336" />
    <!-- 0x0bda = 3034, 0x5740 = 22336 -->
    
    <!-- Allow any UVC camera as fallback -->
    <usb-device class="14" subclass="1" protocol="0" />
    <!-- Class 14 = Video, Subclass 1 = Video Control -->
</resources>
```

## Testing

### Simple Camera Test

```kotlin
// MainActivity.kt
private fun testStereoCamera() {
    Thread {
        if (initStereoCamera()) {
            Log.i(TAG, "✅ Stereo camera initialized!")
            
            // Get one frame
            val result = getStereoCameraFrame()
            if (result != null) {
                Log.i(TAG, "✅ Got stereo frame!")
                Log.i(TAG, "Left: ${result.leftWidth}x${result.leftHeight}")
                Log.i(TAG, "Right: ${result.rightWidth}x${result.rightHeight}")
            }
        } else {
            Log.e(TAG, "❌ Failed to initialize stereo camera")
        }
    }.start()
}

// Native declaration
external fun initStereoCamera(): Boolean
external fun getStereoCameraFrame(): StereoCameraFrame?

data class StereoCameraFrame(
    val leftWidth: Int,
    val leftHeight: Int,
    val rightWidth: Int,
    val rightHeight: Int
)
```

## Troubleshooting

### USB Device Not Found

1. Check USB connection:
   ```bash
   adb shell lsusb
   ```

2. Grant USB permission manually:
   - Settings → Developer Options → USB debugging
   - When prompted, allow USB device access

3. Check vendor/product IDs:
   ```bash
   adb shell cat /sys/kernel/debug/usb/devices | grep -A 5 "Vendor"
   ```

### Permission Denied

Add to AndroidManifest.xml:
```xml
<uses-permission android:name="android.permission.MANAGE_USB" />
```

### Camera Opens But No Frames

- Check format support:
  ```cpp
  uvc_print_diag(m_devh, stderr);
  ```

- Try different formats (YUYV, MJPEG, GRAY8)

## Expected Output

```
I/StereoCamera: Initializing XREAL Light stereo camera...
I/StereoCamera: Searching for XREAL Light OV580 camera...
I/StereoCamera: Device found, opening...
I/StereoCamera: Device opened:
I/StereoCamera:   Vendor:  Realtek
I/StereoCamera:   Product: XREAL Light
I/StereoCamera: Configuring stream: 1280x480 @ 30 FPS (GRAY8)
I/StereoCamera: ✅ Streaming started!
I/StereoCamera: ✅ Stereo camera initialized successfully!
I/StereoCamera:    Resolution: 1280x480 @ 30 FPS
```
