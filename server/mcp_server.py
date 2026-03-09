#!/usr/bin/env python3
"""
XREAL NativeAR — MCP Tool Server
=================================
Claude Code에 XREAL 앱 지원 도구를 제공하는 MCP 서버.

도구 목록:
  - evolution_pending: 대기 중인 개선 요청 조회
  - evolution_resolve: 요청 해결 완료 표시
  - evolution_stats: 진화 요청 통계
  - app_build: 앱 빌드 (assembleDebug)
  - app_apk_path: 최신 APK 경로 반환
  - model_list: 사용 가능한 모델 목록
  - model_deploy: 학습된 모델을 앱 assets에 배포
  - server_status: 백업 서버 상태 확인
  - llm_query: 로컬 LLM에 질의
  - tailscale_status: 테일스케일 피어 상태

사용법:
  claude --mcp "python D:/XREALNativeAR/XREALNativeAR/server/mcp_server.py"

또는 ~/.claude/claude_desktop_config.json 에 등록.
"""

import json
import subprocess
import sqlite3
import time
from pathlib import Path

from mcp.server.fastmcp import FastMCP

# ── 경로 설정 ──
PROJECT_ROOT = Path("D:/XREALNativeAR/XREALNativeAR")
SERVER_DIR = PROJECT_ROOT / "server"
DB_PATH = SERVER_DIR / "backup.db"
MODEL_DIR = Path("D:/XREALNativeAR/yolo_training/export")
APP_ASSETS = PROJECT_ROOT / "app/src/main/assets"
APK_PATH = PROJECT_ROOT / "app/build/outputs/apk/debug/app-debug.apk"
API_KEY = "3ztg3rzTith-vViCK7FLe5SQNUqqACKTojyDX1oiNe0"
JAVA_HOME = "F:/AndroidAndroid Studio/jbr"
LLM_URL = "http://localhost:8080/v1/chat/completions"

mcp = FastMCP("xreal-nativear")


def get_db():
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    return conn


# ── Evolution Tools ──

@mcp.tool()
def evolution_pending(limit: int = 20) -> str:
    """대기 중인 앱 개선 요청 조회. AI 에이전트가 작업 중 필요하다고 요청한 도구/기능/버그 리포트."""
    try:
        db = get_db()
        rows = db.execute(
            "SELECT id, request_type, category, title, description, priority, created_at "
            "FROM evolution_requests WHERE status='pending' "
            "ORDER BY priority DESC, created_at ASC LIMIT ?", (limit,)
        ).fetchall()
        db.close()
        if not rows:
            return "대기 중인 요청 없음"
        result = []
        for r in rows:
            result.append(f"#{r['id']} [{r['request_type']}/{r['category']}] P{r['priority']} — {r['title']}\n  {r['description'][:200]}")
        return f"대기 요청 {len(rows)}건:\n\n" + "\n\n".join(result)
    except Exception as e:
        return f"Error: {e}"


@mcp.tool()
def evolution_resolve(request_id: int, resolution: str) -> str:
    """개선 요청을 해결 완료로 표시. 코드 수정 후 호출."""
    try:
        db = get_db()
        db.execute(
            "UPDATE evolution_requests SET status='resolved', resolved_at=?, resolution=? WHERE id=?",
            (int(time.time() * 1000), resolution, request_id)
        )
        db.commit()
        db.close()
        return f"#{request_id} 해결 완료: {resolution}"
    except Exception as e:
        return f"Error: {e}"


@mcp.tool()
def evolution_stats() -> str:
    """진화 요청 통계 (전체/대기/해결/카테고리별)."""
    try:
        db = get_db()
        total = db.execute("SELECT COUNT(*) FROM evolution_requests").fetchone()[0]
        pending = db.execute("SELECT COUNT(*) FROM evolution_requests WHERE status='pending'").fetchone()[0]
        resolved = db.execute("SELECT COUNT(*) FROM evolution_requests WHERE status='resolved'").fetchone()[0]
        by_cat = db.execute(
            "SELECT category, COUNT(*) FROM evolution_requests WHERE status='pending' GROUP BY category"
        ).fetchall()
        db.close()
        cats = ", ".join(f"{r[0]}:{r[1]}" for r in by_cat) if by_cat else "없음"
        return f"전체: {total}, 대기: {pending}, 해결: {resolved}\n카테고리별 대기: {cats}"
    except Exception as e:
        return f"Error: {e}"


# ── Build Tools ──

@mcp.tool()
def app_build() -> str:
    """앱 빌드 실행 (assembleDebug). 완료 후 APK 경로 반환."""
    try:
        result = subprocess.run(
            ["bash", "-c", f'cd "{PROJECT_ROOT}" && JAVA_HOME=\'{JAVA_HOME}\' ./gradlew :app:assembleDebug'],
            capture_output=True, text=True, timeout=600, cwd=str(PROJECT_ROOT)
        )
        if result.returncode == 0:
            size_mb = APK_PATH.stat().st_size / (1024 * 1024) if APK_PATH.exists() else 0
            return f"BUILD SUCCESSFUL\nAPK: {APK_PATH} ({size_mb:.1f}MB)"
        else:
            # 마지막 30줄만 반환
            lines = result.stdout.split("\n")[-30:]
            return f"BUILD FAILED:\n" + "\n".join(lines)
    except subprocess.TimeoutExpired:
        return "BUILD TIMEOUT (10분 초과)"
    except Exception as e:
        return f"Error: {e}"


@mcp.tool()
def app_apk_path() -> str:
    """최신 빌드된 APK 파일 경로 및 정보."""
    if APK_PATH.exists():
        size_mb = APK_PATH.stat().st_size / (1024 * 1024)
        mtime = time.strftime("%Y-%m-%d %H:%M", time.localtime(APK_PATH.stat().st_mtime))
        return f"APK: {APK_PATH}\n크기: {size_mb:.1f}MB\n수정: {mtime}"
    return "APK 없음 — app_build 먼저 실행"


# ── Model Tools ──

@mcp.tool()
def model_list() -> str:
    """서버에 배포된 TFLite 모델 목록."""
    if not MODEL_DIR.exists():
        return f"모델 디렉토리 없음: {MODEL_DIR}"
    models = list(MODEL_DIR.glob("*.tflite"))
    if not models:
        return "배포된 모델 없음"
    result = []
    for m in models:
        size_kb = m.stat().st_size / 1024
        mtime = time.strftime("%Y-%m-%d %H:%M", time.localtime(m.stat().st_mtime))
        result.append(f"  {m.name} ({size_kb:.0f}KB, {mtime})")
    return f"모델 {len(models)}개:\n" + "\n".join(result)


@mcp.tool()
def model_deploy(model_name: str) -> str:
    """학습된 모델을 앱 assets 디렉토리에 복사. model_name: tflite 파일명 (확장자 제외)."""
    import shutil
    src = MODEL_DIR / f"{model_name}.tflite"
    if not src.exists():
        return f"모델 없음: {src}"
    dst = APP_ASSETS / f"{model_name}.tflite"
    shutil.copy2(src, dst)
    size_kb = dst.stat().st_size / 1024
    return f"배포 완료: {src.name} → {dst} ({size_kb:.0f}KB)"


# ── Server Tools ──

@mcp.tool()
def server_status() -> str:
    """백업 서버 + LLM 서버 상태 확인."""
    import urllib.request
    results = []

    # Backup server
    try:
        req = urllib.request.Request("http://localhost:8090/api/status")
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = json.loads(resp.read())
            results.append(f"백업 서버: OK (메모리 {data.get('memory_nodes_count', 0)}건, 구조화 {data.get('structured_data_count', 0)}건)")
    except Exception:
        results.append("백업 서버: OFFLINE")

    # LLM server
    try:
        req = urllib.request.Request("http://localhost:8080/v1/models")
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = json.loads(resp.read())
            model = data.get("data", [{}])[0].get("id", "unknown")
            results.append(f"LLM 서버: OK (모델: {model})")
    except Exception:
        results.append("LLM 서버: OFFLINE")

    return "\n".join(results)


@mcp.tool()
def llm_query(prompt: str, max_tokens: int = 512) -> str:
    """로컬 LLM 서버(gemma-3-12b)에 질의. 한국어 가능. 비용 $0."""
    import urllib.request
    body = json.dumps({
        "model": "gemma-3-12b-it",
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": 0.7
    }).encode()
    req = urllib.request.Request(
        LLM_URL,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read())
            text = data["choices"][0]["message"]["content"]
            usage = data.get("usage", {})
            return f"{text}\n\n[tokens: {usage.get('total_tokens', '?')}]"
    except Exception as e:
        return f"LLM Error: {e}"


@mcp.tool()
def tailscale_status() -> str:
    """테일스케일 네트워크 피어 상태 확인."""
    try:
        result = subprocess.run(
            ["tailscale", "status"], capture_output=True, text=True, timeout=10
        )
        return result.stdout if result.returncode == 0 else f"Error: {result.stderr}"
    except Exception as e:
        return f"Error: {e}"


@mcp.tool()
def backup_query(domain: str, limit: int = 10) -> str:
    """백업 DB에서 특정 도메인의 구조화 데이터 조회."""
    try:
        db = get_db()
        rows = db.execute(
            "SELECT data_key, value, updated_at FROM structured_data "
            "WHERE domain=? ORDER BY updated_at DESC LIMIT ?",
            (domain, limit)
        ).fetchall()
        db.close()
        if not rows:
            return f"'{domain}' 도메인에 데이터 없음"
        result = []
        for r in rows:
            val = r['value'][:200] if len(r['value']) > 200 else r['value']
            result.append(f"  {r['data_key']}: {val}")
        return f"'{domain}' {len(rows)}건:\n" + "\n".join(result)
    except Exception as e:
        return f"Error: {e}"


if __name__ == "__main__":
    mcp.run(transport="stdio")
