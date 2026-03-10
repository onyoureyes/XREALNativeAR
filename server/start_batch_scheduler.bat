@echo off
REM === XREAL 통합 배치 스케줄러 ===
REM 매일 새벽 3시에 마이닝 배치 순차 실행
REM   03:00 반성 → 04:00 토픽(일) → 04:30 시계열 → 05:00 예측

cd /d "%~dp0"
set PYTHONIOENCODING=utf-8

echo [%date% %time%] Starting Batch Scheduler (daily 03:00)...
python batch_scheduler.py --schedule
