@echo off
title XREAL Orchestrator v2 (LangChain + LangGraph + CrewAI)
echo ═══════════════════════════════════════════════════════
echo  XREAL Storyteller Orchestrator v2
echo  LangChain + LangGraph + CrewAI
echo ═══════════════════════════════════════════════════════

cd /d "%~dp0"

REM ─── 환경변수 설정 ───
REM Main PC (이 머신)
set VISION_URL=http://127.0.0.1:8080
set BACKUP_URL=http://127.0.0.1:8090

REM Speech PC
set SPEECH_LLM_URL=http://100.121.84.80:8179
set SPEECH_STT_URL=http://100.121.84.80:7860

REM Steam Deck (유동적)
set STEAMDECK_URL=http://100.98.177.14:8080
set STEAMDECK_CLASSIFY_URL=http://100.98.177.14:8082

REM 새 BitNet OpenVINO (빌드 중 — URL 확정 시 설정)
REM set BITNET_OPENVINO_URL=http://100.x.x.x:8080

REM 추가 서버 (필요 시 설정)
REM set EXTRA_LLM_1_URL=http://100.x.x.x:8080
REM set EXTRA_LLM_1_NAME=extra_bitnet
REM set EXTRA_LLM_1_MODEL=bitnet-2.7b
REM set EXTRA_LLM_1_TIER=1

set ORCH_HOST=0.0.0.0
set ORCH_PORT=8091

echo.
echo  LLM Servers:
echo    Vision:   %VISION_URL% (MiniCPM-V 4.5)
echo    Speech:   %SPEECH_LLM_URL% (Gemma-3 4B)
echo    Deck:     %STEAMDECK_URL% (유동적)
echo    Backup:   %BACKUP_URL%
echo.

python orchestrator_v2.py

pause
