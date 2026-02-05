@echo off
set "JAVA_HOME=F:\AndroidAndroid Studio\jbr"
echo Using JAVA_HOME: %JAVA_HOME%
call .\gradlew.bat clean assembleDebug > build_log.txt 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo Build Failed! Check build_log.txt
    exit /b %ERRORLEVEL%
)
echo Build Successful!
