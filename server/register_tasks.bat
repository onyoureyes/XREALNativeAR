@echo off
REM === Windows Task Scheduler 등록 ===
REM 관리자 권한으로 실행 필요
REM 등록 대상:
REM   1. Orchestrator (:8091) — 시작 시 자동 실행
REM   2. Batch Scheduler — 시작 시 자동 실행 (자체 스케줄)

echo Registering XREAL services with Task Scheduler...
echo.

REM 1. Orchestrator — 로그온 시 자동 시작
schtasks /create /tn "XREAL\Orchestrator" ^
    /tr "\"%~dp0start_orchestrator.bat\"" ^
    /sc onlogon ^
    /rl highest ^
    /f
echo   [1/2] Orchestrator registered (on logon)

REM 2. Batch Scheduler — 로그온 시 자동 시작 (내부에서 03:00 대기)
schtasks /create /tn "XREAL\BatchScheduler" ^
    /tr "\"%~dp0start_batch_scheduler.bat\"" ^
    /sc onlogon ^
    /rl highest ^
    /f
echo   [2/2] Batch Scheduler registered (on logon, runs at 03:00)

echo.
echo Done! Verify with: schtasks /query /tn "XREAL\*"
echo.
echo To start now:
echo   start_orchestrator.bat
echo   start_batch_scheduler.bat
echo.
echo To remove:
echo   schtasks /delete /tn "XREAL\Orchestrator" /f
echo   schtasks /delete /tn "XREAL\BatchScheduler" /f
pause
