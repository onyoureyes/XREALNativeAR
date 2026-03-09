#!/usr/bin/env python3
"""
XREAL NativeAR Backup Server
Receives structured_data and memory_nodes from the Android app via REST API.
Stores everything in a local SQLite database.

Usage:
    pip install fastapi uvicorn python-dotenv
    python backup_server.py
"""

import os
import sqlite3
import time
import json
from contextlib import contextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.responses import JSONResponse
import uvicorn
from dotenv import load_dotenv

load_dotenv()

API_KEY = os.getenv("XREAL_API_KEY", "changeme")
DB_PATH = os.getenv("XREAL_DB_PATH", str(Path(__file__).parent / "backup.db"))
HOST = os.getenv("XREAL_HOST", "0.0.0.0")
PORT = int(os.getenv("XREAL_PORT", "8090"))

app = FastAPI(title="XREAL Backup Server")


# ── Database ──

def init_db():
    with get_db() as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS structured_data (
                id INTEGER PRIMARY KEY,
                domain TEXT NOT NULL,
                data_key TEXT NOT NULL,
                value TEXT NOT NULL,
                tags TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL
            )
        """)
        db.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_sd_domain_key
            ON structured_data (domain, data_key)
        """)
        db.execute("""
            CREATE TABLE IF NOT EXISTS memory_nodes (
                id INTEGER PRIMARY KEY,
                timestamp INTEGER,
                role TEXT,
                content TEXT,
                level INTEGER DEFAULT 0,
                latitude REAL,
                longitude REAL,
                metadata TEXT,
                persona_id TEXT,
                received_at INTEGER NOT NULL
            )
        """)
        db.execute("CREATE INDEX IF NOT EXISTS idx_mn_ts ON memory_nodes (timestamp)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_mn_role ON memory_nodes (role)")
    print(f"[DB] Initialized at {DB_PATH}")


@contextmanager
def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.execute("PRAGMA journal_mode=WAL")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


# ── Auth ──

def verify_auth(authorization: str | None):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing or invalid Authorization header")
    token = authorization[7:]
    if token != API_KEY:
        raise HTTPException(status_code=403, detail="Invalid API key")


# ── Endpoints ──

@app.post("/api/sync/structured-data")
async def sync_structured_data(request: Request, authorization: str = Header(None)):
    verify_auth(authorization)
    body = await request.json()

    records = body.get("records", [])
    if not records:
        return JSONResponse({"status": "ok", "synced": 0})

    now = int(time.time() * 1000)
    synced = 0

    with get_db() as db:
        for record in records:
            try:
                db.execute("""
                    INSERT INTO structured_data (id, domain, data_key, value, tags, created_at, updated_at, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(domain, data_key) DO UPDATE SET
                        value = excluded.value,
                        tags = excluded.tags,
                        updated_at = excluded.updated_at,
                        received_at = excluded.received_at
                """, (
                    record.get("id"),
                    record["domain"],
                    record["data_key"],
                    record["value"],
                    record.get("tags"),
                    record.get("created_at", now),
                    record.get("updated_at", now),
                    now
                ))
                synced += 1
            except Exception as e:
                print(f"[WARN] Failed to insert structured_data: {e}")

    print(f"[SYNC] structured_data: {synced}/{len(records)} synced")
    return JSONResponse({"status": "ok", "synced": synced})


@app.post("/api/sync/memory-nodes")
async def sync_memory_nodes(request: Request, authorization: str = Header(None)):
    verify_auth(authorization)
    body = await request.json()

    records = body.get("records", [])
    if not records:
        return JSONResponse({"status": "ok", "synced": 0})

    now = int(time.time() * 1000)
    synced = 0

    with get_db() as db:
        for record in records:
            try:
                db.execute("""
                    INSERT OR REPLACE INTO memory_nodes
                    (id, timestamp, role, content, level, latitude, longitude, metadata, persona_id, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    record.get("id"),
                    record.get("timestamp"),
                    record.get("role"),
                    record.get("content"),
                    record.get("level", 0),
                    record.get("latitude"),
                    record.get("longitude"),
                    record.get("metadata"),
                    record.get("persona_id"),
                    now
                ))
                synced += 1
            except Exception as e:
                print(f"[WARN] Failed to insert memory_node: {e}")

    print(f"[SYNC] memory_nodes: {synced}/{len(records)} synced")
    return JSONResponse({"status": "ok", "synced": synced})


@app.get("/api/status")
async def status():
    """Health check + stats."""
    with get_db() as db:
        sd_count = db.execute("SELECT COUNT(*) FROM structured_data").fetchone()[0]
        mn_count = db.execute("SELECT COUNT(*) FROM memory_nodes").fetchone()[0]
        domains = db.execute(
            "SELECT domain, COUNT(*) FROM structured_data GROUP BY domain ORDER BY COUNT(*) DESC"
        ).fetchall()
    return {
        "status": "running",
        "structured_data_count": sd_count,
        "memory_nodes_count": mn_count,
        "domains": {d: c for d, c in domains},
        "db_path": DB_PATH
    }


@app.get("/api/query/{domain}")
async def query_domain(domain: str, limit: int = 50, authorization: str = Header(None)):
    """Query structured data by domain (for debugging/monitoring)."""
    verify_auth(authorization)
    with get_db() as db:
        rows = db.execute(
            "SELECT id, domain, data_key, value, tags, created_at, updated_at FROM structured_data WHERE domain = ? ORDER BY updated_at DESC LIMIT ?",
            (domain, limit)
        ).fetchall()
    return [{
        "id": r[0], "domain": r[1], "data_key": r[2],
        "value": r[3], "tags": r[4],
        "created_at": r[5], "updated_at": r[6]
    } for r in rows]


# ── Evolution Requests ──

def init_evolution_db():
    with get_db() as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS evolution_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_type TEXT NOT NULL,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                context_json TEXT,
                priority INTEGER DEFAULT 0,
                status TEXT DEFAULT 'pending',
                created_at INTEGER NOT NULL,
                resolved_at INTEGER,
                resolution TEXT
            )
        """)
        db.execute("CREATE INDEX IF NOT EXISTS idx_evo_status ON evolution_requests (status)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_evo_priority ON evolution_requests (priority DESC)")


@app.post("/api/evolution/request")
async def submit_evolution_request(request: Request, authorization: str = Header(None)):
    """앱에서 개선 요청 수신 (자동화된 자가 진화 루프)"""
    verify_auth(authorization)
    body = await request.json()

    now = int(time.time() * 1000)
    req_type = body.get("type", "improvement")         # bug_report, improvement, performance, model_retrain
    category = body.get("category", "general")          # vision, ai, memory, running, hud, audio, system
    title = body.get("title", "Untitled")
    description = body.get("description", "")
    context_json = json.dumps(body.get("context", {}))
    priority = body.get("priority", 0)                  # 0=low, 1=medium, 2=high, 3=critical

    with get_db() as db:
        cursor = db.execute("""
            INSERT INTO evolution_requests (request_type, category, title, description, context_json, priority, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
        """, (req_type, category, title, description, context_json, priority, now))
        req_id = cursor.lastrowid

    print(f"[EVOLUTION] New request #{req_id}: [{req_type}/{category}] {title} (priority={priority})")
    return {"status": "ok", "id": req_id}


@app.get("/api/evolution/pending")
async def get_pending_requests(limit: int = 50, authorization: str = Header(None)):
    """대기 중인 개선 요청 목록 (이 PC에서 조회)"""
    verify_auth(authorization)
    with get_db() as db:
        rows = db.execute("""
            SELECT id, request_type, category, title, description, context_json, priority, status, created_at
            FROM evolution_requests
            WHERE status = 'pending'
            ORDER BY priority DESC, created_at ASC
            LIMIT ?
        """, (limit,)).fetchall()
    return {"requests": [{
        "id": r[0], "type": r[1], "category": r[2], "title": r[3],
        "description": r[4], "context": json.loads(r[5] or "{}"),
        "priority": r[6], "status": r[7], "created_at": r[8]
    } for r in rows]}


@app.get("/api/evolution/all")
async def get_all_requests(limit: int = 100, authorization: str = Header(None)):
    """전체 요청 목록 (해결 완료 포함)"""
    verify_auth(authorization)
    with get_db() as db:
        rows = db.execute("""
            SELECT id, request_type, category, title, description, context_json, priority, status, created_at, resolved_at, resolution
            FROM evolution_requests
            ORDER BY created_at DESC
            LIMIT ?
        """, (limit,)).fetchall()
    return {"requests": [{
        "id": r[0], "type": r[1], "category": r[2], "title": r[3],
        "description": r[4], "context": json.loads(r[5] or "{}"),
        "priority": r[6], "status": r[7], "created_at": r[8],
        "resolved_at": r[9], "resolution": r[10]
    } for r in rows]}


@app.post("/api/evolution/{request_id}/resolve")
async def resolve_request(request_id: int, request: Request, authorization: str = Header(None)):
    """요청 해결 완료 표시 (Claude Code가 코드 수정 후 호출)"""
    verify_auth(authorization)
    body = await request.json()
    resolution = body.get("resolution", "fixed")
    now = int(time.time() * 1000)

    with get_db() as db:
        db.execute("""
            UPDATE evolution_requests SET status = 'resolved', resolved_at = ?, resolution = ?
            WHERE id = ?
        """, (now, resolution, request_id))

    print(f"[EVOLUTION] Request #{request_id} resolved: {resolution}")
    return {"status": "ok"}


@app.get("/api/evolution/stats")
async def evolution_stats(authorization: str = Header(None)):
    """진화 요청 통계"""
    verify_auth(authorization)
    with get_db() as db:
        total = db.execute("SELECT COUNT(*) FROM evolution_requests").fetchone()[0]
        pending = db.execute("SELECT COUNT(*) FROM evolution_requests WHERE status='pending'").fetchone()[0]
        resolved = db.execute("SELECT COUNT(*) FROM evolution_requests WHERE status='resolved'").fetchone()[0]
        by_category = db.execute(
            "SELECT category, COUNT(*) FROM evolution_requests WHERE status='pending' GROUP BY category"
        ).fetchall()
        by_type = db.execute(
            "SELECT request_type, COUNT(*) FROM evolution_requests WHERE status='pending' GROUP BY request_type"
        ).fetchall()
    return {
        "total": total, "pending": pending, "resolved": resolved,
        "pending_by_category": {c: n for c, n in by_category},
        "pending_by_type": {t: n for t, n in by_type}
    }


# ── Remote Tools (앱 AI 에이전트용) ──

# 등록된 원격 도구 정의
REMOTE_TOOLS = {}


def register_tool(name: str, description: str, parameters_json: str, handler):
    """원격 도구 등록 헬퍼"""
    REMOTE_TOOLS[name] = {
        "name": name,
        "description": description,
        "parameters_json": parameters_json,
        "handler": handler
    }


# ── 도구 구현 ──

def tool_query_local_llm(args: dict) -> dict:
    """로컬 LLM(gemma-3-12b)에 질의 — 복잡한 추론, 긴 텍스트 생성 등"""
    import urllib.request
    prompt = args.get("prompt", "")
    max_tokens = int(args.get("max_tokens", 1024))
    system_prompt = args.get("system_prompt")

    messages = []
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})
    messages.append({"role": "user", "content": prompt})

    body = json.dumps({
        "model": "gemma-3-12b-it",
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": 0.7
    }).encode()

    req = urllib.request.Request(
        "http://localhost:8080/v1/chat/completions",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read())
        text = data["choices"][0]["message"]["content"]
        usage = data.get("usage", {})
        return {"result": text, "tokens_used": usage.get("total_tokens", 0)}


def tool_train_routine_classifier(args: dict) -> dict:
    """RoutineClassifier 모델 학습 트리거"""
    import subprocess
    cmd = args.get("command", "demo")  # demo, train, export, test, all
    try:
        result = subprocess.run(
            ["python", str(Path(__file__).parent.parent / "yolo_training" / "train_routine_classifier.py"), cmd],
            capture_output=True, text=True, timeout=600,
            cwd=str(Path(__file__).parent.parent / "yolo_training")
        )
        output = result.stdout[-2000:] if len(result.stdout) > 2000 else result.stdout
        return {"success": result.returncode == 0, "output": output}
    except Exception as e:
        return {"success": False, "error": str(e)}


def tool_search_memory(args: dict) -> dict:
    """백업 DB에서 메모리/구조화 데이터 검색"""
    query = args.get("query", "")
    domain = args.get("domain")
    limit = int(args.get("limit", 10))

    with get_db() as db:
        if domain:
            rows = db.execute(
                "SELECT data_key, value, updated_at FROM structured_data "
                "WHERE domain=? ORDER BY updated_at DESC LIMIT ?",
                (domain, limit)
            ).fetchall()
            return {"results": [{"key": r[0], "value": r[1][:500], "updated_at": r[2]} for r in rows]}
        else:
            rows = db.execute(
                "SELECT content, role, timestamp FROM memory_nodes "
                "WHERE content LIKE ? ORDER BY timestamp DESC LIMIT ?",
                (f"%{query}%", limit)
            ).fetchall()
            return {"results": [{"content": r[0][:500], "role": r[1], "timestamp": r[2]} for r in rows]}


def tool_check_server_health(args: dict) -> dict:
    """서버 상태 + LLM 상태 + 테일스케일 상태"""
    import subprocess
    import urllib.request
    status = {"backup_server": "online"}

    # LLM 서버
    try:
        req = urllib.request.Request("http://localhost:8080/v1/models")
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = json.loads(resp.read())
            model = data.get("data", [{}])[0].get("id", "unknown")
            status["llm_server"] = f"online ({model})"
    except Exception:
        status["llm_server"] = "offline"

    # 테일스케일
    try:
        result = subprocess.run(["tailscale", "status"], capture_output=True, text=True, timeout=5)
        peers = [l.strip() for l in result.stdout.split("\n") if l.strip()]
        status["tailscale_peers"] = len(peers)
        # 폴드4 상태
        fold_line = [l for l in result.stdout.split("\n") if "samsung" in l.lower()]
        status["galaxy_fold4"] = fold_line[0].strip() if fold_line else "not found"
    except Exception:
        status["tailscale"] = "unavailable"

    return status


def tool_request_capability(args: dict) -> dict:
    """AI가 새 도구/기능을 요청 — EvolutionBridge로 전달"""
    title = args.get("title", "")
    description = args.get("description", "")
    req_type = args.get("type", "improvement")
    category = args.get("category", "ai")
    priority = int(args.get("priority", 1))

    now = int(time.time() * 1000)
    with get_db() as db:
        cursor = db.execute(
            "INSERT INTO evolution_requests (request_type, category, title, description, context_json, priority, status, created_at) "
            "VALUES (?, ?, ?, ?, '{}', ?, 'pending', ?)",
            (req_type, category, title, description, priority, now)
        )
        req_id = cursor.lastrowid

    return {"status": "submitted", "id": req_id, "message": f"Request #{req_id} submitted for review"}


# ── 도구 등록 ──

register_tool(
    "query_local_llm",
    "Query the local LLM server (gemma-3-12b, 12B params) for complex reasoning, long text generation, "
    "or any task that benefits from a larger model. Free, unlimited, ~39 tok/s. "
    "Use this when the on-device edge models (270M/1B) are insufficient.",
    '{"type":"object","properties":{"prompt":{"type":"string","description":"The prompt to send"},'
    '"system_prompt":{"type":"string","description":"Optional system prompt"},'
    '"max_tokens":{"type":"integer","description":"Max tokens (default 1024)"}},"required":["prompt"]}',
    tool_query_local_llm
)

register_tool(
    "train_model",
    "Trigger model training on the PC server. Supports RoutineClassifier training pipeline. "
    "Commands: demo (synthetic data test), train (full training), export (TFLite conversion), test (validation), all (end-to-end).",
    '{"type":"object","properties":{"command":{"type":"string","enum":["demo","train","export","test","all"],'
    '"description":"Training command to execute"}},"required":["command"]}',
    tool_train_routine_classifier
)

register_tool(
    "search_memory_backup",
    "Search the backup server's memory database for past conversations, structured data, or knowledge. "
    "Useful for retrieving historical context that may not be on-device.",
    '{"type":"object","properties":{"query":{"type":"string","description":"Search text (for memory content)"},'
    '"domain":{"type":"string","description":"Domain filter for structured data (e.g. evolution, running, schedule)"},'
    '"limit":{"type":"integer","description":"Max results (default 10)"}},"required":[]}',
    tool_search_memory
)

register_tool(
    "check_server_health",
    "Check the health status of all server infrastructure: backup server, LLM server, Tailscale network.",
    '{"type":"object","properties":{}}',
    tool_check_server_health
)

register_tool(
    "request_new_capability",
    "Request a new tool or capability that doesn't exist yet. The request will be queued for implementation by Claude Code on the PC.",
    '{"type":"object","properties":{"title":{"type":"string","description":"Short title of the capability needed"},'
    '"description":{"type":"string","description":"Detailed description of what the tool should do"},'
    '"type":{"type":"string","enum":["new_tool","tool_enhancement","bug_report","performance_issue"],"description":"Request type"},'
    '"category":{"type":"string","enum":["ai","vision","hud","audio","running","memory","system"],"description":"Category"},'
    '"priority":{"type":"integer","description":"0=low, 1=medium, 2=high, 3=critical"}},"required":["title","description"]}',
    tool_request_capability
)


@app.get("/api/tools")
async def list_remote_tools(authorization: str = Header(None)):
    """앱에서 사용 가능한 원격 도구 목록 반환 (AIToolDefinition 호환)"""
    verify_auth(authorization)
    tools = []
    for name, tool in REMOTE_TOOLS.items():
        tools.append({
            "name": tool["name"],
            "description": tool["description"],
            "parameters_json": tool["parameters_json"]
        })
    return {"tools": tools}


@app.post("/api/tools/{tool_name}/execute")
async def execute_remote_tool(tool_name: str, request: Request, authorization: str = Header(None)):
    """원격 도구 실행 — 앱 AI 에이전트가 호출"""
    verify_auth(authorization)
    if tool_name not in REMOTE_TOOLS:
        raise HTTPException(status_code=404, detail=f"Unknown tool: {tool_name}")

    body = await request.json()
    args = body.get("arguments", {})

    try:
        handler = REMOTE_TOOLS[tool_name]["handler"]
        result = handler(args)
        print(f"[TOOL] {tool_name}({json.dumps(args, ensure_ascii=False)[:100]}) → OK")
        return {"success": True, "data": result}
    except Exception as e:
        print(f"[TOOL] {tool_name} FAILED: {e}")
        return {"success": False, "data": {"error": str(e)}}


# ── Model Distribution ──

MODEL_DIR = Path(os.getenv("XREAL_MODEL_DIR", str(Path(__file__).parent.parent / "yolo_training" / "export")))


@app.get("/api/models")
async def list_models(authorization: str = Header(None)):
    """사용 가능한 모델 목록 반환"""
    verify_auth(authorization)
    models = []
    # RoutineClassifier TFLite
    rc_path = MODEL_DIR / "routine_classifier.tflite"
    if rc_path.exists():
        models.append({
            "name": "routine_classifier",
            "filename": "routine_classifier.tflite",
            "size_bytes": rc_path.stat().st_size,
            "modified_at": int(rc_path.stat().st_mtime * 1000),
        })
    # YOLO TFLite
    yolo_path = MODEL_DIR / "custom_yolo_int8.tflite"
    if yolo_path.exists():
        models.append({
            "name": "custom_yolo",
            "filename": "custom_yolo_int8.tflite",
            "size_bytes": yolo_path.stat().st_size,
            "modified_at": int(yolo_path.stat().st_mtime * 1000),
        })
    # 추가 모델 파일 자동 탐색
    if MODEL_DIR.exists():
        for f in MODEL_DIR.glob("*.tflite"):
            if f.name not in ("routine_classifier.tflite", "custom_yolo_int8.tflite"):
                models.append({
                    "name": f.stem,
                    "filename": f.name,
                    "size_bytes": f.stat().st_size,
                    "modified_at": int(f.stat().st_mtime * 1000),
                })
    return {"models": models}


@app.get("/api/models/{model_name}/info")
async def model_info(model_name: str, authorization: str = Header(None)):
    """특정 모델 메타데이터 반환"""
    verify_auth(authorization)
    filename = f"{model_name}.tflite"
    model_path = MODEL_DIR / filename
    if not model_path.exists():
        raise HTTPException(status_code=404, detail=f"Model not found: {model_name}")
    return {
        "name": model_name,
        "filename": filename,
        "size_bytes": model_path.stat().st_size,
        "modified_at": int(model_path.stat().st_mtime * 1000),
    }


@app.get("/api/models/{model_name}/download")
async def download_model(model_name: str, authorization: str = Header(None)):
    """모델 파일 다운로드 (TFLite binary)"""
    from fastapi.responses import FileResponse
    verify_auth(authorization)
    filename = f"{model_name}.tflite"
    model_path = MODEL_DIR / filename
    if not model_path.exists():
        raise HTTPException(status_code=404, detail=f"Model not found: {model_name}")
    return FileResponse(
        path=str(model_path),
        media_type="application/octet-stream",
        filename=filename,
        headers={"X-Model-Modified": str(int(model_path.stat().st_mtime * 1000))}
    )


@app.post("/api/models/{model_name}/upload")
async def upload_model(model_name: str, request: Request, authorization: str = Header(None)):
    """학습 완료된 모델 업로드 (이 PC에서 학습 후 서버에 배치)"""
    verify_auth(authorization)
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    filename = f"{model_name}.tflite"
    model_path = MODEL_DIR / filename
    body = await request.body()
    if len(body) < 100:
        raise HTTPException(status_code=400, detail="Model file too small")
    model_path.write_bytes(body)
    print(f"[MODEL] Uploaded {model_name}: {len(body)} bytes → {model_path}")
    return {"status": "ok", "name": model_name, "size_bytes": len(body)}


# ── Digital Twin Predictions ──

# 캐시: 예측 결과를 매번 재계산 안 하도록 (TTL 기반)
_prediction_cache = {"weekly": None, "weekly_ts": 0, "daily": None, "daily_ts": 0}
WEEKLY_CACHE_TTL_SEC = 3600 * 6   # 6시간
DAILY_CACHE_TTL_SEC = 1800        # 30분


def _get_predictor():
    from prediction_engine import DigitalTwinPredictor
    return DigitalTwinPredictor()


@app.get("/api/digital-twin/predictions")
async def get_dt_predictions(authorization: str = Header(None)):
    """주간 디지털트윈 프로파일 — 기저선 + 역학 시그니처 + 훈련 구역 + 일일 예측"""
    verify_auth(authorization)
    now = time.time()
    if _prediction_cache["weekly"] and (now - _prediction_cache["weekly_ts"]) < WEEKLY_CACHE_TTL_SEC:
        return _prediction_cache["weekly"]

    try:
        predictor = _get_predictor()
        result = predictor.build_weekly_profile()
        _prediction_cache["weekly"] = result
        _prediction_cache["weekly_ts"] = now
        print(f"[DT] Weekly profile generated ({len(json.dumps(result))} bytes)")
        return result
    except Exception as e:
        print(f"[DT] Weekly profile error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/digital-twin/daily")
async def get_dt_daily(authorization: str = Header(None)):
    """일일 예측 — 회복/부상/수면/최적페이스"""
    verify_auth(authorization)
    now = time.time()
    if _prediction_cache["daily"] and (now - _prediction_cache["daily_ts"]) < DAILY_CACHE_TTL_SEC:
        return _prediction_cache["daily"]

    try:
        predictor = _get_predictor()
        result = predictor.build_daily_predictions()
        _prediction_cache["daily"] = result
        _prediction_cache["daily_ts"] = now
        print(f"[DT] Daily predictions generated")
        return result
    except Exception as e:
        print(f"[DT] Daily prediction error: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/digital-twin/pace/{distance_km}")
async def get_dt_pace(distance_km: float, authorization: str = Header(None)):
    """특정 거리 최적 페이스 예측"""
    verify_auth(authorization)
    try:
        predictor = _get_predictor()
        return predictor.predict_optimal_pace(distance_km)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/digital-twin/critical-speed")
async def get_dt_critical_speed(authorization: str = Header(None)):
    """Critical Speed + LTHR + 훈련 구역"""
    verify_auth(authorization)
    try:
        predictor = _get_predictor()
        cs = predictor.get_critical_speed()
        lthr = cs.get("lthr_bpm", 165)
        cs["training_zones"] = predictor.get_training_zones(lthr)
        return cs
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/digital-twin/invalidate-cache")
async def invalidate_dt_cache(authorization: str = Header(None)):
    """캐시 무효화 (새 데이터 싱크 후 호출)"""
    verify_auth(authorization)
    _prediction_cache["weekly"] = None
    _prediction_cache["daily"] = None
    print("[DT] Cache invalidated")
    return {"status": "ok"}


# 원격 도구에 예측 도구 등록
def tool_get_digital_twin(args: dict) -> dict:
    """디지털트윈 예측 결과 조회 — AI 에이전트용"""
    prediction_type = args.get("type", "daily")
    predictor = _get_predictor()
    if prediction_type == "weekly":
        return predictor.build_weekly_profile()
    elif prediction_type == "recovery":
        return predictor.predict_recovery_state()
    elif prediction_type == "injury":
        return predictor.predict_injury_risk()
    elif prediction_type == "sleep":
        return predictor.predict_sleep_quality()
    elif prediction_type == "pace":
        distance = float(args.get("distance_km", 5.0))
        return predictor.predict_optimal_pace(distance)
    else:
        return predictor.build_daily_predictions()


register_tool(
    "get_digital_twin",
    "Get digital twin predictions: recovery state, injury risk, optimal pace, sleep quality. "
    "Types: daily (all predictions), weekly (full profile), recovery, injury, sleep, pace.",
    '{"type":"object","properties":{"type":{"type":"string","enum":["daily","weekly","recovery","injury","sleep","pace"],'
    '"description":"Prediction type"},"distance_km":{"type":"number","description":"Target distance for pace prediction (default 5.0)"}},'
    '"required":["type"]}',
    tool_get_digital_twin
)


# ── Main ──

if __name__ == "__main__":
    init_db()
    init_evolution_db()
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    print(f"[SERVER] Starting on {HOST}:{PORT}")
    print(f"[SERVER] API Key: {API_KEY[:4]}{'*' * (len(API_KEY) - 4)}")
    print(f"[SERVER] Model dir: {MODEL_DIR}")
    print(f"[SERVER] Remote tools ({len(REMOTE_TOOLS)}): {', '.join(REMOTE_TOOLS.keys())}")
    uvicorn.run(app, host=HOST, port=PORT)
