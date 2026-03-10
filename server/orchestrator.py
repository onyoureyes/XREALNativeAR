#!/usr/bin/env python3
"""
XREAL Storyteller Orchestrator — Main PC 두뇌 서버

모든 서비스의 관문. Fold → Orchestrator → 각 서비스로 라우팅.

서비스 토폴로지:
  Fold (100.87.7.62)       → 이 서버 (:8091)
  MiniCPM-V (:8080)        ← 비전 분석 요청
  Speech PC (:7860/:8179)  ← STT/감정/TTS
  Steam Deck (:8082)       ← 경량 분류
  Backup DB (:8090)        ← 메모리 저장
  Mem0 (로컬)              ← 사실 추출
  ChromaDB (로컬)          ← 시맨틱 검색

Usage:
    pip install -r requirements-orchestrator.txt
    python orchestrator.py
"""

import os
import time
import json
import logging
import asyncio
from datetime import datetime
from typing import Optional
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
import uvicorn

# ─── Config ───

VISION_URL = os.getenv("VISION_URL", "http://127.0.0.1:8080")
SPEECH_STT_URL = os.getenv("SPEECH_STT_URL", "http://100.121.84.80:7860")
SPEECH_LLM_URL = os.getenv("SPEECH_LLM_URL", "http://100.121.84.80:8179")
STEAMDECK_URL = os.getenv("STEAMDECK_URL", "http://100.98.177.14:8082")
BACKUP_URL = os.getenv("BACKUP_URL", "http://127.0.0.1:8090")
HOST = os.getenv("ORCH_HOST", "0.0.0.0")
PORT = int(os.getenv("ORCH_PORT", "8091"))

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
log = logging.getLogger("orchestrator")

# ─── HTTP Client ───

http_client: httpx.AsyncClient = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global http_client
    http_client = httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=5.0))
    log.info(f"Orchestrator starting on {HOST}:{PORT}")
    log.info(f"  Vision:  {VISION_URL}")
    log.info(f"  Speech:  {SPEECH_STT_URL}")
    log.info(f"  Backup:  {BACKUP_URL}")
    yield
    await http_client.aclose()

app = FastAPI(title="XREAL Storyteller Orchestrator", lifespan=lifespan)

# ─── Models ───

class VisionRequest(BaseModel):
    """Fold가 프레임을 보내면 MiniCPM-V로 분석"""
    image_base64: str
    prompt: str = "이 장면을 한국어로 상세히 묘사해주세요. 사람, 물체, 행동, 분위기를 포함해주세요."
    max_tokens: int = 512

class VisionResponse(BaseModel):
    description: str
    latency_ms: int
    timestamp: str

class ChatRequest(BaseModel):
    """Fold에서 대화 요청"""
    messages: list[dict]  # [{"role": "user", "content": "..."}]
    system_prompt: str = ""
    max_tokens: int = 1024
    provider: str = "vision"  # "vision" (MiniCPM-V), "speech" (Gemma 4B), "steamdeck" (Gemma 4B)

class ChatResponse(BaseModel):
    text: str
    provider: str
    latency_ms: int

class MemoryIngestRequest(BaseModel):
    """Fold에서 메모리 저장 요청"""
    content: str
    role: str = "observation"
    metadata: Optional[dict] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    persona_id: Optional[str] = None

class EpisodeEvent(BaseModel):
    """실시간 에피소드 이벤트 (비전/음성/센서 통합)"""
    event_type: str  # "vision", "speech", "sensor", "interaction"
    content: str
    timestamp: float = Field(default_factory=time.time)
    metadata: Optional[dict] = None

class HealthResponse(BaseModel):
    status: str
    uptime_seconds: float
    services: dict

# ─── State ───

start_time = time.time()
episode_buffer: list[EpisodeEvent] = []  # 최근 에피소드 버퍼 (L1 워킹 메모리)
MAX_EPISODE_BUFFER = 50


# ─── Endpoints ───

@app.get("/health", response_model=HealthResponse)
async def health():
    """서비스 상태 + 연결된 서비스 헬스 체크"""
    services = {}
    for name, url in [("vision", VISION_URL), ("backup", BACKUP_URL)]:
        try:
            r = await http_client.get(f"{url}/health", timeout=3.0)
            services[name] = "ok" if r.status_code == 200 else f"error:{r.status_code}"
        except Exception as e:
            services[name] = f"unreachable:{type(e).__name__}"

    return HealthResponse(
        status="ok",
        uptime_seconds=round(time.time() - start_time, 1),
        services=services
    )


@app.post("/v1/vision/analyze", response_model=VisionResponse)
async def analyze_vision(req: VisionRequest):
    """비전 분석: Fold → Orchestrator → MiniCPM-V"""
    t0 = time.time()

    # MiniCPM-V는 OpenAI-compatible API
    payload = {
        "model": "minicpm-v",
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": req.prompt},
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{req.image_base64}"}}
                ]
            }
        ],
        "max_tokens": req.max_tokens
    }

    try:
        r = await http_client.post(f"{VISION_URL}/v1/chat/completions", json=payload)
        r.raise_for_status()
        data = r.json()
        description = data["choices"][0]["message"]["content"]
    except httpx.TimeoutException:
        raise HTTPException(504, "Vision server timeout (60s)")
    except Exception as e:
        log.error(f"Vision error: {e}")
        raise HTTPException(502, f"Vision server error: {e}")

    latency = int((time.time() - t0) * 1000)

    # 에피소드 버퍼에 추가
    _add_episode(EpisodeEvent(
        event_type="vision",
        content=description,
        metadata={"latency_ms": latency, "prompt": req.prompt[:100]}
    ))

    return VisionResponse(
        description=description,
        latency_ms=latency,
        timestamp=datetime.now().isoformat()
    )


@app.post("/v1/chat", response_model=ChatResponse)
async def chat(req: ChatRequest):
    """LLM 대화: provider에 따라 라우팅"""
    t0 = time.time()

    url_map = {
        "vision": VISION_URL,
        "speech": SPEECH_LLM_URL,
        "steamdeck": STEAMDECK_URL,
    }
    base_url = url_map.get(req.provider, VISION_URL)

    messages = req.messages.copy()
    if req.system_prompt:
        messages.insert(0, {"role": "system", "content": req.system_prompt})

    payload = {
        "model": "default",
        "messages": messages,
        "max_tokens": req.max_tokens,
    }

    try:
        r = await http_client.post(f"{base_url}/v1/chat/completions", json=payload)
        r.raise_for_status()
        data = r.json()
        text = data["choices"][0]["message"]["content"]
    except httpx.TimeoutException:
        raise HTTPException(504, f"LLM timeout ({req.provider})")
    except Exception as e:
        log.error(f"Chat error ({req.provider}): {e}")
        raise HTTPException(502, f"LLM error: {e}")

    latency = int((time.time() - t0) * 1000)

    return ChatResponse(text=text, provider=req.provider, latency_ms=latency)


@app.post("/v1/episode")
async def ingest_episode(event: EpisodeEvent):
    """에피소드 이벤트 수신 (비전/음성/센서 통합 스트림)"""
    _add_episode(event)
    return {"status": "ok", "buffer_size": len(episode_buffer)}


@app.get("/v1/episode/recent")
async def get_recent_episodes(limit: int = 10):
    """최근 에피소드 조회 (L1 워킹 메모리)"""
    return {"episodes": [e.model_dump() for e in episode_buffer[-limit:]]}


@app.post("/v1/memory/save")
async def save_memory(req: MemoryIngestRequest):
    """메모리 저장: backup_server로 전달"""
    payload = {
        "content": req.content,
        "role": req.role,
        "metadata": json.dumps(req.metadata) if req.metadata else None,
        "latitude": req.latitude,
        "longitude": req.longitude,
        "persona_id": req.persona_id,
    }

    try:
        r = await http_client.post(f"{BACKUP_URL}/api/memory", json=payload)
        r.raise_for_status()
        return r.json()
    except Exception as e:
        log.error(f"Memory save error: {e}")
        raise HTTPException(502, f"Backup server error: {e}")


@app.get("/v1/context/summary")
async def get_context_summary():
    """현재 컨텍스트 요약 (이야기꾼이 참조)"""
    recent = episode_buffer[-10:]
    vision_events = [e for e in recent if e.event_type == "vision"]
    speech_events = [e for e in recent if e.event_type == "speech"]
    sensor_events = [e for e in recent if e.event_type == "sensor"]

    return {
        "timestamp": datetime.now().isoformat(),
        "episode_count": len(episode_buffer),
        "recent_vision": [e.content[:200] for e in vision_events[-3:]],
        "recent_speech": [e.content[:200] for e in speech_events[-3:]],
        "recent_sensor": [e.content[:200] for e in sensor_events[-3:]],
        "summary": _build_context_summary(recent)
    }


# ─── Internal ───

def _add_episode(event: EpisodeEvent):
    """에피소드 버퍼에 추가 (L1 워킹 메모리)"""
    episode_buffer.append(event)
    if len(episode_buffer) > MAX_EPISODE_BUFFER:
        # 오래된 것부터 제거 (나중에 L2로 flush 추가)
        episode_buffer.pop(0)


def _build_context_summary(events: list[EpisodeEvent]) -> str:
    """최근 이벤트에서 간단한 컨텍스트 요약 생성"""
    if not events:
        return "컨텍스트 없음"

    parts = []
    for e in events[-5:]:
        ts = datetime.fromtimestamp(e.timestamp).strftime("%H:%M:%S")
        parts.append(f"[{ts} {e.event_type}] {e.content[:100]}")

    return "\n".join(parts)


# ─── Main ───

if __name__ == "__main__":
    uvicorn.run("orchestrator:app", host=HOST, port=PORT, reload=True, log_level="info")
