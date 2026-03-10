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

from mem0_service import Mem0Service
from prediction_engine import DigitalTwinPredictor
from episode_store import EpisodeStore
from semantic_card_store import SemanticCardStore

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

mem0_svc: Mem0Service = None
predictor: DigitalTwinPredictor = None
episode_db: EpisodeStore = None
card_store: SemanticCardStore = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    global http_client, mem0_svc
    http_client = httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=5.0))

    # Mem0 초기화 (실패해도 서버는 기동 — 나중에 재시도 가능)
    try:
        mem0_svc = Mem0Service()
        log.info("Mem0 서비스 초기화 완료")
    except Exception as e:
        log.warning(f"Mem0 초기화 실패 (서버는 계속 가동): {e}")

    # 예측 엔진 초기화
    try:
        predictor = DigitalTwinPredictor()
        log.info("예측 엔진 초기화 완료")
    except Exception as e:
        log.warning(f"예측 엔진 초기화 실패: {e}")

    # 에피소드 영속 저장소 초기화 (L2)
    try:
        episode_db = EpisodeStore()
        log.info(f"에피소드 DB 초기화 완료 (기존 {episode_db.count()}건)")
    except Exception as e:
        log.warning(f"에피소드 DB 초기화 실패: {e}")

    # 시맨틱 카드 저장소 초기화 (L3)
    try:
        card_store = SemanticCardStore()
        log.info(f"시맨틱 카드 초기화 완료 (기존 {card_store.count()}장)")
    except Exception as e:
        log.warning(f"시맨틱 카드 초기화 실패: {e}")

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

class Mem0AddRequest(BaseModel):
    """Mem0에 사실/관찰 추가"""
    content: str
    user_id: str = "teacher"
    event_type: str = "observation"  # vision, speech, sensor, interaction, reflection
    extra_context: str = ""

class Mem0SearchRequest(BaseModel):
    """Mem0 시맨틱 검색"""
    query: str
    user_id: str = "teacher"
    limit: int = 5

class CardAddRequest(BaseModel):
    """시맨틱 카드 추가"""
    card_id: Optional[str] = None  # 미지정 시 자동 생성
    content: str
    event_type: str = "observation"
    user_id: str = "teacher"
    person: Optional[str] = None
    location: Optional[str] = None
    emotion: Optional[str] = None  # positive, negative, neutral, mixed
    tags: Optional[list[str]] = None
    metadata: Optional[dict] = None

class CardSearchRequest(BaseModel):
    """시맨틱 카드 하이브리드 검색"""
    query: str
    n_results: int = 10
    event_type: Optional[str] = None
    user_id: Optional[str] = None
    person: Optional[str] = None
    location: Optional[str] = None
    emotion: Optional[str] = None
    date: Optional[str] = None  # YYYY-MM-DD
    date_from: Optional[str] = None
    date_to: Optional[str] = None
    hour_min: Optional[int] = None  # 0-23
    hour_max: Optional[int] = None
    has_tag: Optional[str] = None

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

    services["mem0"] = "ok" if mem0_svc is not None else "not_initialized"
    services["predictor"] = "ok" if predictor is not None else "not_initialized"
    services["episode_db"] = f"ok ({episode_db.count()} episodes)" if episode_db is not None else "not_initialized"
    services["card_store"] = f"ok ({card_store.count()} cards)" if card_store is not None else "not_initialized"

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


# ─── Mem0 Endpoints ───

@app.post("/v1/mem0/add")
async def mem0_add(req: Mem0AddRequest):
    """Mem0에 사실 추가 → A.U.D.N. 자동 수행"""
    if mem0_svc is None:
        raise HTTPException(503, "Mem0 서비스 미초기화")
    result = mem0_svc.add_episode(
        event_type=req.event_type,
        content=req.content,
        user_id=req.user_id,
        extra_context=req.extra_context,
    )
    return result


@app.post("/v1/mem0/search")
async def mem0_search(req: Mem0SearchRequest):
    """Mem0 시맨틱 검색"""
    if mem0_svc is None:
        raise HTTPException(503, "Mem0 서비스 미초기화")
    results = mem0_svc.search(
        query=req.query,
        user_id=req.user_id,
        limit=req.limit,
    )
    return {"results": results, "count": len(results)}


@app.get("/v1/mem0/all")
async def mem0_get_all(user_id: str = "teacher"):
    """Mem0 전체 기억 조회"""
    if mem0_svc is None:
        raise HTTPException(503, "Mem0 서비스 미초기화")
    results = mem0_svc.get_all(user_id=user_id)
    return {"results": results, "count": len(results)}


@app.delete("/v1/mem0/{memory_id}")
async def mem0_delete(memory_id: str):
    """Mem0 특정 기억 삭제"""
    if mem0_svc is None:
        raise HTTPException(503, "Mem0 서비스 미초기화")
    ok = mem0_svc.delete(memory_id)
    return {"deleted": ok, "memory_id": memory_id}


@app.get("/v1/mem0/context")
async def mem0_context(situation: str = "", user_id: str = "teacher", limit: int = 10):
    """이야기꾼용 컨텍스트 — 현재 상황 관련 기억 요약"""
    if mem0_svc is None:
        raise HTTPException(503, "Mem0 서비스 미초기화")
    context = mem0_svc.get_context_for_storyteller(
        situation=situation, user_id=user_id, limit=limit
    )
    return {"context": context, "situation": situation}


@app.get("/v1/episode/history")
async def get_episode_history(date: str = None, limit: int = 50):
    """에피소드 이력 조회 (L2 SQLite, 서버 재시작 후에도 유지)"""
    if episode_db is None:
        raise HTTPException(503, "에피소드 DB 미초기화")
    if date:
        episodes = episode_db.get_by_date(date)
    else:
        episodes = episode_db.get_recent(limit)
    return {"episodes": episodes, "count": len(episodes)}


@app.get("/v1/episode/stats")
async def get_episode_stats(days: int = 7):
    """에피소드 일별 통계"""
    if episode_db is None:
        raise HTTPException(503, "에피소드 DB 미초기화")
    return {"stats": episode_db.get_daily_stats(days), "total": episode_db.count()}


@app.post("/v1/episode/flush-to-mem0")
async def flush_episodes_to_mem0(limit: int = 10):
    """최근 에피소드를 Mem0에 일괄 저장 (L1 → L3 플러시)"""
    if mem0_svc is None:
        raise HTTPException(503, "Mem0 서비스 미초기화")

    recent = episode_buffer[-limit:]
    results = []
    for ep in recent:
        r = mem0_svc.add_episode(
            event_type=ep.event_type,
            content=ep.content,
            extra_context=json.dumps(ep.metadata) if ep.metadata else "",
        )
        results.append({
            "event_type": ep.event_type,
            "facts_extracted": len(r.get("results", [])),
        })

    return {"flushed": len(results), "details": results}


# ─── Prediction Endpoints ───

@app.get("/v1/predict/daily")
async def predict_daily():
    """일일 예측 (회복, 부상위험, 수면, 최적페이스)"""
    if predictor is None:
        raise HTTPException(503, "예측 엔진 미초기화")
    return predictor.build_daily_predictions()


@app.get("/v1/predict/weekly-profile")
async def predict_weekly():
    """주간 디지털트윈 프로파일"""
    if predictor is None:
        raise HTTPException(503, "예측 엔진 미초기화")
    return predictor.build_weekly_profile()


@app.get("/v1/predict/optimal-pace")
async def predict_pace(distance_km: float = 5.0):
    """목표 거리별 최적 페이스"""
    if predictor is None:
        raise HTTPException(503, "예측 엔진 미초기화")
    return predictor.predict_optimal_pace(distance_km)


# ─── Semantic Card Endpoints (L3 하이브리드 검색) ───

@app.post("/v1/cards/add")
async def card_add(req: CardAddRequest):
    """시맨틱 카드 추가"""
    if card_store is None:
        raise HTTPException(503, "시맨틱 카드 스토어 미초기화")
    card_id = req.card_id or f"card_{int(time.time() * 1000)}"
    result_id = card_store.add_card(
        card_id=card_id,
        content=req.content,
        event_type=req.event_type,
        user_id=req.user_id,
        person=req.person,
        location=req.location,
        emotion=req.emotion,
        tags=req.tags,
        extra_metadata=req.metadata,
    )
    return {"card_id": result_id, "total_cards": card_store.count()}


@app.post("/v1/cards/search")
async def card_search(req: CardSearchRequest):
    """시맨틱 카드 하이브리드 검색 (벡터 유사도 + 메타데이터 필터)"""
    if card_store is None:
        raise HTTPException(503, "시맨틱 카드 스토어 미초기화")
    results = card_store.search(
        query=req.query,
        n_results=req.n_results,
        event_type=req.event_type,
        user_id=req.user_id,
        person=req.person,
        location=req.location,
        emotion=req.emotion,
        date=req.date,
        date_from=req.date_from,
        date_to=req.date_to,
        hour_min=req.hour_min,
        hour_max=req.hour_max,
        has_tag=req.has_tag,
    )
    return {"results": results, "count": len(results)}


@app.get("/v1/cards/{card_id}")
async def card_get(card_id: str):
    """특정 시맨틱 카드 조회"""
    if card_store is None:
        raise HTTPException(503, "시맨틱 카드 스토어 미초기화")
    card = card_store.get_card(card_id)
    if card is None:
        raise HTTPException(404, f"카드 없음: {card_id}")
    return card


@app.delete("/v1/cards/{card_id}")
async def card_delete(card_id: str):
    """시맨틱 카드 삭제"""
    if card_store is None:
        raise HTTPException(503, "시맨틱 카드 스토어 미초기화")
    ok = card_store.delete_card(card_id)
    return {"deleted": ok, "card_id": card_id}


@app.get("/v1/cards/stats/overview")
async def card_stats():
    """시맨틱 카드 통계"""
    if card_store is None:
        raise HTTPException(503, "시맨틱 카드 스토어 미초기화")
    return card_store.get_stats()


@app.post("/v1/cards/ingest-episodes")
async def card_ingest_episodes(limit: int = 20):
    """최근 에피소드를 시맨틱 카드로 일괄 변환"""
    if card_store is None:
        raise HTTPException(503, "시맨틱 카드 스토어 미초기화")

    recent = episode_buffer[-limit:]
    ingested = 0
    for ep in recent:
        ep_dict = ep.model_dump()
        try:
            card_store.add_episode_as_card(ep_dict)
            ingested += 1
        except Exception as e:
            log.warning(f"카드 변환 실패: {e}")

    return {"ingested": ingested, "total_cards": card_store.count()}


# ─── Mining Trigger Endpoints ───

@app.post("/v1/mining/topic")
async def trigger_topic_mining():
    """BERTopic 토픽 마이닝 수동 트리거 (보통 주간 자동)"""
    import subprocess
    import sys
    proc = subprocess.Popen(
        [sys.executable, "topic_miner.py"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return {"status": "started", "pid": proc.pid, "schedule": "weekly (Sun 04:00)"}


@app.post("/v1/mining/timeseries")
async def trigger_timeseries_mining():
    """STUMPY 시계열 마이닝 수동 트리거 (보통 일일 자동)"""
    import subprocess
    import sys
    proc = subprocess.Popen(
        [sys.executable, "timeseries_miner.py"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return {"status": "started", "pid": proc.pid, "schedule": "daily (04:30)"}


@app.post("/v1/mining/knowledge-graph")
async def trigger_kg_build():
    """Graphiti 지식 그래프 구축 수동 트리거 (보통 월간 자동)"""
    import subprocess
    import sys
    proc = subprocess.Popen(
        [sys.executable, "knowledge_graph.py"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return {"status": "started", "pid": proc.pid, "schedule": "monthly (1st 05:00)"}


@app.post("/v1/mining/reflection")
async def trigger_reflection():
    """야간 반성 수동 트리거 (보통 매일 새벽 3시 자동)"""
    import subprocess
    import sys
    proc = subprocess.Popen(
        [sys.executable, "nightly_reflection.py"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return {"status": "started", "pid": proc.pid, "schedule": "daily (03:00)"}


@app.post("/v1/mining/forecast")
async def trigger_forecast():
    """예측 엔진 수동 트리거 (보통 매일 새벽 5시 자동)"""
    import subprocess
    import sys
    proc = subprocess.Popen(
        [sys.executable, "forecast_engine.py"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return {"status": "started", "pid": proc.pid, "schedule": "daily (05:00)"}


@app.get("/v1/predict/forecast")
async def get_forecast():
    """최신 행동 예측 리포트 조회 (NeuralProphet + TFT)"""
    from pathlib import Path
    forecast_dir = Path("data/forecasts")
    if not forecast_dir.exists():
        raise HTTPException(404, "예측 리포트 없음")

    # 최신 파일
    files = sorted(forecast_dir.glob("forecast_*.json"), reverse=True)
    if not files:
        raise HTTPException(404, "예측 리포트 없음")

    report = json.loads(files[0].read_text(encoding="utf-8"))
    return report


@app.get("/v1/storyteller/context")
async def get_storyteller_context():
    """이야기꾼용 통합 컨텍스트 — 모든 마이닝/예측 결과를 하나로 합침

    이 엔드포인트는 이야기꾼이 사용자와 대화할 때 참조할 배경 지식을 제공.
    순서: 최근 에피소드 → Mem0 기억 → 예측 → 마이닝 인사이트 → 브리핑
    """
    from pathlib import Path
    context = {
        "generated_at": datetime.now().isoformat(),
        "sections": {},
    }

    # 1. 최근 에피소드 요약 (L1)
    recent = episode_buffer[-10:] if episode_buffer else []
    if recent:
        context["sections"]["recent_episodes"] = [
            {"time": datetime.fromtimestamp(e.timestamp).strftime("%H:%M"),
             "type": e.event_type, "content": e.content[:200]}
            for e in recent
        ]

    # 2. Mem0 관련 기억 (L3)
    if mem0_svc:
        try:
            memories = mem0_svc.search("오늘 중요한 일", user_id="teacher", limit=5)
            context["sections"]["relevant_memories"] = memories
        except Exception as e:
            log.warning(f"Mem0 검색 실패: {e}")

    # 3. 최신 행동 예측
    try:
        forecast_dir = Path("data/forecasts")
        files = sorted(forecast_dir.glob("forecast_*.json"), reverse=True)
        if files:
            forecast = json.loads(files[0].read_text(encoding="utf-8"))
            context["sections"]["forecast"] = {
                "insights": forecast.get("insights", []),
                "model": forecast.get("activity_forecast", {}).get("model", "unknown"),
            }
    except Exception as e:
        log.warning(f"예측 로드 실패: {e}")

    # 4. 최신 마이닝 인사이트 (토픽, 시계열, 지식그래프)
    for name, pattern in [
        ("topic_insights", "data/topic_reports/topic_report_*.json"),
        ("timeseries_insights", "data/timeseries_reports/timeseries_*.json"),
        ("kg_insights", "data/kg_reports/kg_report_*.json"),
    ]:
        try:
            files = sorted(Path(".").glob(pattern), reverse=True)
            if files:
                report = json.loads(files[0].read_text(encoding="utf-8"))
                context["sections"][name] = report.get("insights", [])
        except Exception as e:
            log.warning(f"{name} 로드 실패: {e}")

    # 5. 최신 아침 브리핑
    try:
        briefing_dir = Path("data/briefings")
        files = sorted(briefing_dir.glob("briefing_*.md"), reverse=True)
        if files:
            context["sections"]["morning_briefing"] = files[0].read_text(encoding="utf-8")[:2000]
    except Exception as e:
        log.warning(f"브리핑 로드 실패: {e}")

    # 6. 디지털 트윈 예측 (기존 prediction_engine)
    if predictor:
        try:
            daily = predictor.predict_daily()
            context["sections"]["digital_twin"] = {
                "recovery": daily.get("recovery_pct"),
                "optimal_pace": daily.get("optimal_pace"),
                "training_load": daily.get("training_load"),
            }
        except Exception:
            pass

    return context


@app.post("/v1/mining/run-all")
async def trigger_all_mining():
    """모든 마이닝 배치를 순차적으로 실행 (테스트/수동 트리거용)"""
    import subprocess
    import sys

    results = {}
    scripts = [
        ("reflection", "nightly_reflection.py"),
        ("topic", "topic_miner.py"),
        ("timeseries", "timeseries_miner.py"),
        ("forecast", "forecast_engine.py"),
    ]

    for name, script in scripts:
        try:
            proc = subprocess.Popen(
                [sys.executable, script],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            results[name] = {"status": "started", "pid": proc.pid}
        except Exception as e:
            results[name] = {"status": "error", "error": str(e)}

    return {"status": "all_started", "jobs": results}


# ─── Internal ───

def _add_episode(event: EpisodeEvent):
    """에피소드 버퍼에 추가 (L1 워킹 메모리) + L2 영속 저장"""
    episode_buffer.append(event)
    if len(episode_buffer) > MAX_EPISODE_BUFFER:
        episode_buffer.pop(0)

    # L2 영속 저장 (SQLite)
    if episode_db is not None:
        try:
            episode_db.save(
                event_type=event.event_type,
                content=event.content,
                timestamp=event.timestamp,
                metadata=event.metadata,
            )
        except Exception as e:
            log.warning(f"에피소드 L2 저장 실패: {e}")


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
