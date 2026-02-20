---
description: Build, Deploy to Galaxy Fold 4 (wireless ADB), and Start Logcat
---

## Prerequisites
- Phone and PC must be on the same WiFi network
- Wireless debugging enabled on phone (Settings → Developer Options → Wireless Debugging)

## Steps

1. **Pair (first time only)** — Get pairing code and port from phone's wireless debugging screen:
```powershell
& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" pair <IP>:<PAIRING_PORT> <CODE>
```

2. **Connect** — Use the main wireless debugging port (different from pairing port):
```powershell
& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" connect <IP>:<PORT>
```

// turbo
3. **Build the hardware test app**:
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; ./gradlew :hardware-test-app:assembleDebug
```

4. **Install APK**:
```powershell
& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <IP>:<PORT> install -r "c:\Users\User\.gemini\antigravity\scratch\XREALNativeAR\hardware-test-app\build\outputs\apk\debug\hardware-test-app-debug.apk"
```

5. **Force-stop and restart app**:
```powershell
& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <IP>:<PORT> shell am force-stop com.xreal.testapp
& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <IP>:<PORT> shell am start -n com.xreal.testapp/.MainActivity
```

6. **Verify installed version**:
```powershell
& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <IP>:<PORT> shell "dumpsys package com.xreal.testapp | grep -E 'versionCode|versionName'"
```

## Important Notes
- **Always bump `versionCode`/`versionName`** in `hardware-test-app/build.gradle` before deploying so the user can verify the correct build
- **Wireless logcat is unreliable** for this app — use the on-screen `sLog()` callback instead
- When XREAL glasses are connected to the phone, USB cable to PC cannot be used simultaneously
- Read `xreal-hardware-standalone/PROTOCOL.md` for USB communication protocol details
