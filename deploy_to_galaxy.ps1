$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$ADB = "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$IP = "10.230.170.207:45427" 

Write-Host "🔗 Connecting to Device via Wireless Debugging..."
& $ADB connect $IP

Write-Host "🔨 Building APK..."
.\gradlew assembleDebug

if ($LASTEXITCODE -eq 0) {
    Write-Host "🚀 Installing to Device..."
    & $ADB install -r .\app\build\outputs\apk\debug\app-debug.apk
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "🏁 Launching App on Galaxy Fold 4..."
        & $ADB shell am start -n com.xreal.nativear/com.xreal.nativear.MainActivity
        Write-Host "📝 Starting Logcat Stream (Press Ctrl+C to stop)..."
        & $ADB logcat -v time MainActivity:I GeminiClient:I MemoryToolHandler:I XREALCamera:I OCR:I *:S
    }
    else {
        Write-Host "❌ Installation Failed."
    }
}
else {
    Write-Host "❌ Build Failed. Aborting."
}
