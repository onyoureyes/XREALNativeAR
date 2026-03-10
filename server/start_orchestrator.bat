@echo off
REM === XREAL Orchestrator Server (:8091) ===
REM 중앙 관문 — 모든 서비스의 API 게이트웨이
REM 크래시 시 자동 재시작

cd /d "%~dp0"
set PYTHONIOENCODING=utf-8

:loop
echo [%date% %time%] Starting Orchestrator server (port 8091)...
python orchestrator.py

echo [%date% %time%] Orchestrator crashed! Restarting in 5 seconds...
timeout /t 5 /nobreak >nul
goto loop
