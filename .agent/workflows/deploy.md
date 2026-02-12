---
description: Build, Deploy to Galaxy Fold 4, and Start Logcat
---

// turbo-all
1. Build the APK using Gradle
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; ./gradlew assembleDebug
```

2. Connect to the device via Wireless Debugging (Ensure IP matches)
```powershell
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe connect 10.230.170.207:37857
```

3. Install the APK
```powershell
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

4. Launch the application
```powershell
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe shell am start -n com.xreal.nativear/com.xreal.nativear.MainActivity
```

5. Start Logcat with filters
```powershell
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -v time MainActivity:I GeminiClient:I MemoryToolHandler:I XREALCamera:I OCR:I *:S
```
