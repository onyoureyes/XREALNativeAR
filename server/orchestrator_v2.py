#!/usr/bin/env python3
"""
XREAL Storyteller Orchestrator v2 — LangChain + LangGraph + CrewAI

기존 orchestrator.py의 모든 엔드포인트를 유지하면서,
내부를 오픈소스 프레임워크 기반으로 전환.

변경점:
  - LLM 호출 → LLMPool (분산 서버 자동 페일오버)
  - 이야기꾼 → LangGraph StateGraph (상태 관리 + 체크포인트)
  - 전문가 팀 → CrewAI (역할 기반 에이전트)
  - 도구 → LangChain Tools (구조화된 도구 정의)

기존과 호환:
  - 모든 /v1/* 엔드포인트 동일
  - OrchestratorClient.kt 변경 불필요
  - 추가 엔드포인트: /v2/storyteller/*, /v2/expert/*

Usage:
    pip install -r requirements-v2.txt
    python orchestrator_v2.py
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

# 기존 서비스 (재사용)
from mem0_service import Mem0Service
from prediction_engine import DigitalTwinPredictor
from episode_store import EpisodeStore
from semantic_card_store import SemanticCardStore

# v2 모듈
from llm_pool import LLMPool, ServerCapability, ServerTier
from langchain_tools import init_tools, get_all_tools
from expert_crews import ExpertCrewManager

# ─── Config ───

HOST = os.getenv("ORCH_HOST", "0.0.0.0")
PORT = int(os.getenv("ORCH_PORT", "8091"))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
)
log = logging.getLogger("orchestrator_v2")

# ─── Global State ───

llm_pool: LLMPool = None
mem0_svc: Mem0Service = None
predictor: DigitalTwinPredictor = None
episode_db: EpisodeStore = None
card_store: SemanticCardStore = None
crew_manager: ExpertCrewManager = None
debate_manager = None
storyteller_graph = None
storyteller_state = None

# L1 에피소드 버퍼 (기존 호환)
episode_buffer: list[dict] = []
MAX_EPISODE_BUFFER = 50
start_time = time.time()


# ─── Lifespan ───

@asynccontextmanager
async def lifespan(app: FastAPI):
    global llm_pool, mem0_svc, predictor, episode_db, card_store
    global crew_manager, debate_manager, storyteller_graph, storyteller_state

    # 1. LLM Pool 시작 (모든 서버 헬스체크)
    llm_pool = LLMPool()
    await llm_pool.start()
    log.info(f"LLM Pool: {llm_pool.get_status()['healthy']}/{llm_pool.get_status()['total_servers']} healthy")

    # 2. 기존 서비스 초기화
    try:
        mem0_svc = Mem0Service()
        log.info("Mem0 서비스 초기화 완료")
    except Exception as e:
        log.warning(f"Mem0 초기화 실패: {e}")

    try:
        predictor = DigitalTwinPredictor()
        log.info("예측 엔진 초기화 완료")
    except Exception as e:
        log.warning(f"예측 엔진 초기화 실패: {e}")

    try:
        episode_db = EpisodeStore()
        log.info(f"에피소드 DB 초기화 완료 ({episode_db.count()}건)")
    except Exception as e:
        log.warning(f"에피소드 DB 초기화 실패: {e}")

    try:
        card_store = SemanticCardStore()
        log.info(f"시맨틱 카드 초기화 완료 ({card_store.count()}장)")
    except Exception as e:
        log.warning(f"시맨틱 카드 초기화 실패: {e}")

    # 3. LangChain 도구 초기화
    init_tools(mem0_svc, episode_db, card_store, predictor, episode_buffer)
    log.info(f"LangChain 도구 초기화 완료 ({len(get_all_tools())}개)")

    # 4. CrewAI 전문가 팀 + Debate Manager
    crew_manager = ExpertCrewManager(llm_pool)
    log.info(f"전문가 팀 초기화 완료 ({len(crew_manager.list_experts())}명)")

    from debate_graph import DebateManager
    debate_manager = DebateManager(llm_pool)
    log.info("Multi-Agent Debate 초기화 완료")

    # 5. LangGraph 이야기꾼 (선택적 — langgraph 설치 시)
    try:
        from storyteller_graph import build_storyteller_graph, create_initial_state
        storyteller_graph = build_storyteller_graph()
        storyteller_state = create_initial_state()
        log.info("LangGraph 이야기꾼 초기화 완료")
    except ImportError:
        log.warning("langgraph 미설치 — 이야기꾼 그래프 비활성")
    except Exception as e:
        log.warning(f"이야기꾼 그래프 초기화 실패: {e}")

    log.info(f"═══ Orchestrator v2 시작 ({HOST}:{PORT}) ═══")

    yield

    # Cleanup
    await llm_pool.stop()
    log.info("Orchestrator v2 종료")


app = FastAPI(
    title="XREAL Storyteller Orchestrator v2",
    description="LangChain + LangGraph + CrewAI 기반",
    lifespan=lifespan,
)


# ═══════════════════════════════════════════
# 기존 호환 엔드포인트 (v1) — OrchestratorClient.kt 변경 불필요
# ═══════════════════════════════════════════

# ─── Models (기존 호환) ───

class VisionRequest(BaseModel):
    image_base64: str
    prompt: str = "이 장면을 한국어로 상세히 묘사해주세요."
    max_tokens: int = 512

class ChatRequest(BaseModel):
    messages: list[dict]
    system_prompt: str = ""
    max_tokens: int = 1024
    provider: str = "vision"

class EpisodeEvent(BaseModel):
    event_type: str
    content: str
    timestamp: float = Field(default_factory=time.time)
    metadata: Optional[dict] = None

class MemoryIngestRequest(BaseModel):
    content: str
    role: str = "observation"
    metadata: Optional[dict] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    persona_id: Optional[str] = None

class Mem0AddRequest(BaseModel):
    content: str
    user_id: str = "teacher"
    event_type: str = "observation"
    extra_context: str = ""

class Mem0SearchRequest(BaseModel):
    query: str
    user_id: str = "teacher"
    limit: int = 5

class CardAddRequest(BaseModel):
    card_id: Optional[str] = None
    content: str
    event_type: str = "observation"
    user_id: str = "teacher"
    person: Optional[str] = None
    location: Optional[str] = None
    emotion: Optional[str] = None
    tags: Optional[list[str]] = None
    metadata: Optional[dict] = None

class CardSearchRequest(BaseModel):
    query: str
    n_results: int = 10
    event_type: Optional[str] = None
    user_id: Optional[str] = None
    person: Optional[str] = None
    location: Optional[str] = None
    emotion: Optional[str] = None
    date: Optional[str] = None
    date_from: Optional[str] = None
    date_to: Optional[str] = None
    hour_min: Optional[int] = None
    hour_max: Optional[int] = None
    has_tag: Optional[str] = None


# ─── Health ───

@app.get("/health")
async def health():
    pool_status = llm_pool.get_status() if llm_pool else {"healthy": 0, "total_servers": 0}
    return {
        "status": "ok",
        "version": "v2",
        "uptime_seconds": round(time.time() - start_time, 1),
        "services": {
            "llm_pool": f"{pool_status['healthy']}/{pool_status['total_servers']} healthy",
            "mem0": "ok" if mem0_svc else "not_initialized",
            "predictor": "ok" if predictor else "not_initialized",
            "episode_db": f"ok ({episode_db.count()} episodes)" if episode_db else "not_initialized",
            "card_store": f"ok ({card_store.count()} cards)" if card_store else "not_initialized",
            "storyteller_graph": "ok" if storyteller_graph else "not_available",
            "crew_manager": f"ok ({len(crew_manager.list_experts())} experts)" if crew_manager else "not_available",
            "debate_manager": "ok" if debate_manager else "not_available",
        }
    }


# ─── Vision (v1 호환 — 이제 LLMPool 사용) ───

@app.post("/v1/vision/analyze")
async def analyze_vision(req: VisionRequest):
    t0 = time.time()
    try:
        response = await llm_pool.chat_with_vision(
            prompt=req.prompt,
            image_base64=req.image_base64,
            max_tokens=req.max_tokens,
        )
    except RuntimeError as e:
        raise HTTPException(502, str(e))

    _add_episode(EpisodeEvent(
        event_type="vision",
        content=response.text,
        metadata={"latency_ms": response.latency_ms, "server": response.server_name},
    ))

    return {
        "description": response.text,
        "latency_ms": response.latency_ms,
        "timestamp": datetime.now().isoformat(),
        "server": response.server_name,
    }


# ─── Chat (v1 호환 — LLMPool 자동 라우팅) ───

@app.post("/v1/chat")
async def chat(req: ChatRequest):
    t0 = time.time()

    # provider 힌트 → 티어 매핑
    tier_map = {
        "vision": ServerTier.HIGH,
        "speech": ServerTier.MEDIUM,
        "steamdeck": ServerTier.LOW,
    }
    prefer_tier = tier_map.get(req.provider)

    # vision provider면 비전 기능 필요 여부 체크
    capability = ServerCapability.TEXT
    if req.provider == "vision":
        # 메시지에 이미지가 포함되어 있는지 확인
        for msg in req.messages:
            content = msg.get("content", "")
            if isinstance(content, list):
                for part in content:
                    if isinstance(part, dict) and part.get("type") == "image_url":
                        capability = ServerCapability.VISION
                        break

    messages = req.messages.copy()
    if req.system_prompt:
        messages.insert(0, {"role": "system", "content": req.system_prompt})

    try:
        response = await llm_pool.chat(
            prompt="",
            messages=messages,
            max_tokens=req.max_tokens,
            prefer_tier=prefer_tier,
            require_capability=capability,
        )
    except RuntimeError as e:
        raise HTTPException(502, str(e))

    return {
        "text": response.text,
        "provider": response.server_name,
        "latency_ms": response.latency_ms,
        "fallback_used": response.fallback_used,
    }


# ─── Episode (v1 호환) ───

@app.post("/v1/episode")
async def ingest_episode(event: EpisodeEvent):
    _add_episode(event)
    return {"status": "ok", "buffer_size": len(episode_buffer)}

@app.get("/v1/episode/recent")
async def get_recent_episodes(limit: int = 10):
    return {"episodes": episode_buffer[-limit:]}

@app.get("/v1/episode/history")
async def get_episode_history(date: str = None, limit: int = 50):
    if episode_db is None:
        raise HTTPException(503, "에피소드 DB 미초기화")
    episodes = episode_db.get_by_date(date) if date else episode_db.get_recent(limit)
    return {"episodes": episodes, "count": len(episodes)}

@app.get("/v1/episode/stats")
async def get_episode_stats(days: int = 7):
    if episode_db is None:
        raise HTTPException(503, "에피소드 DB 미초기화")
    return {"stats": episode_db.get_daily_stats(days), "total": episode_db.count()}

@app.post("/v1/episode/flush-to-mem0")
async def flush_episodes_to_mem0(limit: int = 10):
    if mem0_svc is None:
        raise HTTPException(503, "Mem0 미초기화")
    recent = episode_buffer[-limit:]
    results = []
    for ep in recent:
        r = mem0_svc.add_episode(
            event_type=ep.get("event_type", "unknown"),
            content=ep.get("content", ""),
            extra_context=json.dumps(ep.get("metadata")) if ep.get("metadata") else "",
        )
        results.append({"event_type": ep.get("event_type"), "facts": len(r.get("results", []))})
    return {"flushed": len(results), "details": results}


# ─── Memory (v1 호환) ───

@app.post("/v1/memory/save")
async def save_memory(req: MemoryIngestRequest):
    backup_url = os.getenv("BACKUP_URL", "http://127.0.0.1:8090")
    async with httpx.AsyncClient(timeout=30.0) as client:
        try:
            r = await client.post(f"{backup_url}/api/memory", json={
                "content": req.content, "role": req.role,
                "metadata": json.dumps(req.metadata) if req.metadata else None,
                "latitude": req.latitude, "longitude": req.longitude,
                "persona_id": req.persona_id,
            })
            r.raise_for_status()
            return r.json()
        except Exception as e:
            raise HTTPException(502, f"Backup server error: {e}")


# ─── Mem0 (v1 호환) ───

@app.post("/v1/mem0/add")
async def mem0_add(req: Mem0AddRequest):
    if mem0_svc is None: raise HTTPException(503, "Mem0 미초기화")
    return mem0_svc.add_episode(event_type=req.event_type, content=req.content,
                                user_id=req.user_id, extra_context=req.extra_context)

@app.post("/v1/mem0/search")
async def mem0_search(req: Mem0SearchRequest):
    if mem0_svc is None: raise HTTPException(503, "Mem0 미초기화")
    results = mem0_svc.search(query=req.query, user_id=req.user_id, limit=req.limit)
    return {"results": results, "count": len(results)}

@app.get("/v1/mem0/all")
async def mem0_get_all(user_id: str = "teacher"):
    if mem0_svc is None: raise HTTPException(503, "Mem0 미초기화")
    results = mem0_svc.get_all(user_id=user_id)
    return {"results": results, "count": len(results)}

@app.delete("/v1/mem0/{memory_id}")
async def mem0_delete(memory_id: str):
    if mem0_svc is None: raise HTTPException(503, "Mem0 미초기화")
    return {"deleted": mem0_svc.delete(memory_id), "memory_id": memory_id}

@app.get("/v1/mem0/context")
async def mem0_context(situation: str = "", user_id: str = "teacher", limit: int = 10):
    if mem0_svc is None: raise HTTPException(503, "Mem0 미초기화")
    context = mem0_svc.get_context_for_storyteller(situation=situation, user_id=user_id, limit=limit)
    return {"context": context, "situation": situation}


# ─── Prediction (v1 호환) ───

@app.get("/v1/predict/daily")
async def predict_daily():
    if predictor is None: raise HTTPException(503, "예측 엔진 미초기화")
    return predictor.build_daily_predictions()

@app.get("/v1/predict/weekly-profile")
async def predict_weekly():
    if predictor is None: raise HTTPException(503, "예측 엔진 미초기화")
    return predictor.build_weekly_profile()

@app.get("/v1/predict/optimal-pace")
async def predict_pace(distance_km: float = 5.0):
    if predictor is None: raise HTTPException(503, "예측 엔진 미초기화")
    return predictor.predict_optimal_pace(distance_km)

@app.get("/v1/predict/forecast")
async def get_forecast():
    from pathlib import Path
    files = sorted(Path("data/forecasts").glob("forecast_*.json"), reverse=True)
    if not files: raise HTTPException(404, "예측 리포트 없음")
    return json.loads(files[0].read_text(encoding="utf-8"))


# ─── Semantic Cards (v1 호환) ───

@app.post("/v1/cards/add")
async def card_add(req: CardAddRequest):
    if card_store is None: raise HTTPException(503, "카드 스토어 미초기화")
    card_id = req.card_id or f"card_{int(time.time() * 1000)}"
    result_id = card_store.add_card(card_id=card_id, content=req.content,
        event_type=req.event_type, user_id=req.user_id, person=req.person,
        location=req.location, emotion=req.emotion, tags=req.tags, extra_metadata=req.metadata)
    return {"card_id": result_id, "total_cards": card_store.count()}

@app.post("/v1/cards/search")
async def card_search(req: CardSearchRequest):
    if card_store is None: raise HTTPException(503, "카드 스토어 미초기화")
    results = card_store.search(query=req.query, n_results=req.n_results,
        event_type=req.event_type, user_id=req.user_id, person=req.person,
        location=req.location, emotion=req.emotion, date=req.date,
        date_from=req.date_from, date_to=req.date_to,
        hour_min=req.hour_min, hour_max=req.hour_max, has_tag=req.has_tag)
    return {"results": results, "count": len(results)}

@app.get("/v1/cards/{card_id}")
async def card_get(card_id: str):
    if card_store is None: raise HTTPException(503, "카드 스토어 미초기화")
    card = card_store.get_card(card_id)
    if card is None: raise HTTPException(404, f"카드 없음: {card_id}")
    return card

@app.delete("/v1/cards/{card_id}")
async def card_delete(card_id: str):
    if card_store is None: raise HTTPException(503, "카드 스토어 미초기화")
    return {"deleted": card_store.delete_card(card_id), "card_id": card_id}

@app.get("/v1/cards/stats/overview")
async def card_stats():
    if card_store is None: raise HTTPException(503, "카드 스토어 미초기화")
    return card_store.get_stats()

@app.post("/v1/cards/ingest-episodes")
async def card_ingest_episodes(limit: int = 20):
    if card_store is None: raise HTTPException(503, "카드 스토어 미초기화")
    recent = episode_buffer[-limit:]
    ingested = 0
    for ep in recent:
        try:
            card_store.add_episode_as_card(ep)
            ingested += 1
        except Exception as e:
            log.warning(f"카드 변환 실패: {e}")
    return {"ingested": ingested, "total_cards": card_store.count()}


# ─── Mining Triggers (v1 호환) ───

@app.post("/v1/mining/{job_name}")
async def trigger_mining(job_name: str):
    import subprocess, sys
    script_map = {
        "topic": "topic_miner.py",
        "timeseries": "timeseries_miner.py",
        "knowledge-graph": "knowledge_graph.py",
        "reflection": "nightly_reflection.py",
        "forecast": "forecast_engine.py",
    }
    script = script_map.get(job_name)
    if not script:
        raise HTTPException(404, f"알 수 없는 마이닝 작업: {job_name}")
    proc = subprocess.Popen([sys.executable, script], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return {"status": "started", "pid": proc.pid, "job": job_name}

@app.post("/v1/mining/run-all")
async def trigger_all_mining():
    import subprocess, sys
    results = {}
    for name, script in [("reflection", "nightly_reflection.py"), ("topic", "topic_miner.py"),
                         ("timeseries", "timeseries_miner.py"), ("forecast", "forecast_engine.py")]:
        try:
            proc = subprocess.Popen([sys.executable, script], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            results[name] = {"status": "started", "pid": proc.pid}
        except Exception as e:
            results[name] = {"status": "error", "error": str(e)}
    return {"status": "all_started", "jobs": results}


# ─── Storyteller Context (v1 호환) ───

@app.get("/v1/storyteller/context")
async def get_storyteller_context():
    from pathlib import Path
    context = {"generated_at": datetime.now().isoformat(), "sections": {}}

    # 최근 에피소드
    if episode_buffer:
        context["sections"]["recent_episodes"] = [
            {"time": datetime.fromtimestamp(e.get("timestamp", 0)).strftime("%H:%M"),
             "type": e.get("event_type"), "content": e.get("content", "")[:200]}
            for e in episode_buffer[-10:]
        ]

    # Mem0 기억
    if mem0_svc:
        try:
            context["sections"]["relevant_memories"] = mem0_svc.search(
                "오늘 중요한 일", user_id="teacher", limit=5)
        except Exception: pass

    # 예측 리포트
    try:
        files = sorted(Path("data/forecasts").glob("forecast_*.json"), reverse=True)
        if files:
            forecast = json.loads(files[0].read_text(encoding="utf-8"))
            context["sections"]["forecast"] = {
                "insights": forecast.get("insights", []),
                "model": forecast.get("activity_forecast", {}).get("model", "unknown"),
            }
    except Exception: pass

    # 마이닝 인사이트
    for name, pattern in [("topic_insights", "data/topic_reports/topic_report_*.json"),
                          ("timeseries_insights", "data/timeseries_reports/timeseries_*.json"),
                          ("kg_insights", "data/kg_reports/kg_report_*.json")]:
        try:
            files = sorted(Path(".").glob(pattern), reverse=True)
            if files:
                report = json.loads(files[0].read_text(encoding="utf-8"))
                context["sections"][name] = report.get("insights", [])
        except Exception: pass

    # 아침 브리핑
    try:
        files = sorted(Path("data/briefings").glob("briefing_*.md"), reverse=True)
        if files:
            context["sections"]["morning_briefing"] = files[0].read_text(encoding="utf-8")[:2000]
    except Exception: pass

    # 디지털 트윈
    if predictor:
        try:
            daily = predictor.predict_daily()
            context["sections"]["digital_twin"] = {
                "recovery": daily.get("recovery_pct"),
                "optimal_pace": daily.get("optimal_pace"),
                "training_load": daily.get("training_load"),
            }
        except Exception: pass

    return context

@app.get("/v1/context/summary")
async def get_context_summary():
    recent = episode_buffer[-10:]
    return {
        "timestamp": datetime.now().isoformat(),
        "episode_count": len(episode_buffer),
        "recent_vision": [e.get("content", "")[:200] for e in recent if e.get("event_type") == "vision"][-3:],
        "recent_speech": [e.get("content", "")[:200] for e in recent if e.get("event_type") == "speech"][-3:],
        "recent_sensor": [e.get("content", "")[:200] for e in recent if e.get("event_type") == "sensor"][-3:],
        "summary": _build_context_summary(recent),
    }


# ═══════════════════════════════════════════
# v2 신규 엔드포인트 — LangGraph + CrewAI
# ═══════════════════════════════════════════

class StorytellerInput(BaseModel):
    """이야기꾼 그래프 실행 입력"""
    situation: str = "UNKNOWN"
    episodes: list[dict] = []
    heart_rate: Optional[float] = None
    visible_people: list[str] = []
    current_emotion: Optional[str] = None
    user_speech: Optional[str] = None

class ExpertRequest(BaseModel):
    """전문가 위임 요청"""
    domain: str  # behavior/lesson/health/social/planning/crisis
    query: str
    context: Optional[dict] = None

class TeamRequest(BaseModel):
    """복수 전문가 위임 요청"""
    domains: list[str]
    query: str
    context: Optional[dict] = None


@app.post("/v2/storyteller/tick")
async def storyteller_tick(req: StorytellerInput):
    """이야기꾼 한 사이클 실행 — LangGraph 상태 그래프

    Fold의 OrchestratorClient가 주기적으로 호출.
    에피소드 + 상황 정보 → 내러티브/질문/전문가 위임 결과 반환.
    """
    if storyteller_graph is None:
        # LangGraph 없으면 폴백 — 직접 LLM 호출
        return await _storyteller_fallback(req)

    global storyteller_state
    from storyteller_graph import (
        parse_narrative_response, parse_question_response,
        STORYTELLER_SYSTEM_TEMPLATE,
        QUESTION_SYSTEM_TEMPLATE,
    )
    from langchain_core.messages import HumanMessage

    # ── 배경지식 수집 (JSON 프로필 + Mem0 + 컨텍스트) ──
    background_parts = []
    user_profile = ""

    # 1) 로컬 JSON 프로필 (LLM 호출 없이 즉시 로드)
    try:
        from pathlib import Path
        profile_path = Path("data/user_profile.json")
        if profile_path.exists():
            import json as _json
            profile_data = _json.loads(profile_path.read_text(encoding="utf-8"))
            profile_lines = [
                f"- 직업: {profile_data.get('occupation', '알 수 없음')}",
                f"- 설명: {profile_data.get('description', '')}",
                f"- 작업환경: {profile_data.get('workspace', '')}",
                f"- 관심사: {', '.join(profile_data.get('interests', []))}",
            ]
            hints = profile_data.get("context_hints", [])
            if hints:
                profile_lines.extend(f"- {h}" for h in hints)
            user_profile = "## 사용자 정보\n" + "\n".join(profile_lines)
    except Exception as e:
        log.warning(f"프로필 JSON 로드 실패: {e}")

    # 2) Mem0에서 상황 관련 기억 검색 (타임아웃 5초, 실패 시 스킵)
    if mem0_svc:
        try:
            import asyncio as _aio
            situation_query = f"{req.situation} {req.current_emotion or ''} {' '.join(req.visible_people)}"
            relevant_memories = await _aio.wait_for(
                _aio.to_thread(
                    mem0_svc.search,
                    query=situation_query.strip(),
                    user_id="teacher",
                    limit=5,
                ),
                timeout=5.0,
            )
            if relevant_memories:
                mem_lines = []
                for m in relevant_memories:
                    text = m.get("memory", m.get("text", ""))
                    if text:
                        mem_lines.append(f"- {text}")
                if mem_lines:
                    background_parts.append("관련 기억:\n" + "\n".join(mem_lines))
        except _aio.TimeoutError:
            log.warning("Mem0 검색 타임아웃 (5s) — 스킵")
        except Exception as e:
            log.warning(f"Mem0 배경지식 검색 실패: {e}")

    # 3) 최근 에피소드 요약
    if episode_buffer:
        recent_eps = episode_buffer[-5:]
        ep_lines = [f"- [{e.get('event_type','?')}] {e.get('content','')[:80]}" for e in recent_eps]
        background_parts.append("최근 활동:\n" + "\n".join(ep_lines))

    # 4) 아침 브리핑 (있으면)
    try:
        briefings = sorted(Path("data/briefings").glob("briefing_*.md"), reverse=True)
        if briefings:
            briefing_text = briefings[0].read_text(encoding="utf-8")[:500]
            background_parts.append(f"오늘의 브리핑:\n{briefing_text}")
    except Exception:
        pass

    background_context = "\n\n".join(background_parts) if background_parts else None

    # 시스템 프롬프트에 사용자 프로필 주입
    system_prompt_narrate = STORYTELLER_SYSTEM_TEMPLATE.format(user_profile=user_profile)
    system_prompt_question = QUESTION_SYSTEM_TEMPLATE.format(user_profile=user_profile)

    # ── 상태 업데이트 ──
    storyteller_state["episodes"] = req.episodes
    storyteller_state["current_situation"] = req.situation
    storyteller_state["heart_rate"] = req.heart_rate
    storyteller_state["visible_people"] = req.visible_people
    storyteller_state["user_speech"] = req.user_speech
    storyteller_state["_background_context"] = background_context

    if req.current_emotion:
        storyteller_state["previous_emotion"] = storyteller_state.get("current_emotion")
        storyteller_state["current_emotion"] = req.current_emotion

    # 날짜 변경 시 리셋
    today = datetime.now().strftime("%Y-%m-%d")
    if storyteller_state.get("day_date") != today:
        storyteller_state["day_date"] = today
        storyteller_state["beats_today"] = []
        storyteller_state["chapters_today"] = []
        storyteller_state["question_count_today"] = 0

    try:
        # 이전 상태의 beat/question 시간 기억 (비교용)
        prev_beat_time = storyteller_state.get("last_beat_time", 0)
        prev_question_time = storyteller_state.get("last_question_time", 0)

        # LangGraph 실행 (observe → 조건부 → narrate/ask/delegate/end)
        # checkpointer 없음 — 상태는 storyteller_state dict로 수동 관리
        result = await asyncio.to_thread(
            storyteller_graph.invoke,
            dict(storyteller_state),  # TypedDict → plain dict 변환
        )

        if result is None:
            log.warning("Storyteller graph returned None")
            return {"action": "end", "reason": "graph_returned_none"}

        # 결과에서 다음 액션 추출
        output = {"action": result.get("next_action", "end")}

        # narrate 실행 여부 확인
        new_beat_time = result.get("last_beat_time", 0)
        new_question_time = result.get("last_question_time", 0)

        if new_beat_time > prev_beat_time:
            # narrate 노드가 실행됨 — messages에서 프롬프트 추출
            messages = result.get("messages", [])
            prompt_text = ""
            for msg in reversed(messages):
                if hasattr(msg, "content") and isinstance(msg, HumanMessage):
                    prompt_text = msg.content
                    break

            # LLM 호출하여 실제 내러티브 생성
            narrative_text = ""
            try:
                llm_response = await llm_pool.chat(
                    prompt=prompt_text,
                    system=system_prompt_narrate,
                    max_tokens=256,
                )
                narrative_text = llm_response.text
            except Exception as e:
                log.error(f"LLM 내러티브 생성 실패: {e}")
                narrative_text = f"(내러티브 생성 실패: {e})"

            narrative, tone = parse_narrative_response(narrative_text)
            chapter = result.get("current_chapter") or {}
            beat = {
                "timestamp": datetime.now().isoformat(),
                "narrative": narrative,
                "tone": tone,
                "trigger": result.get("_trigger", "reflection"),
                "chapter": chapter.get("title", "") if isinstance(chapter, dict) else "",
            }
            storyteller_state.setdefault("beats_today", []).append(beat)
            output["narrative_beat"] = beat

        elif new_question_time > prev_question_time:
            # ask_question 노드가 실행됨
            messages = result.get("messages", [])
            prompt_text = ""
            for msg in reversed(messages):
                if hasattr(msg, "content") and isinstance(msg, HumanMessage):
                    prompt_text = msg.content
                    break

            # LLM 호출하여 질문 생성
            question_text = ""
            try:
                llm_response = await llm_pool.chat(
                    prompt=prompt_text,
                    system=system_prompt_question,
                    max_tokens=128,
                )
                question_text = llm_response.text
            except Exception as e:
                log.error(f"LLM 질문 생성 실패: {e}")
                question_text = f"(질문 생성 실패: {e})"

            question = parse_question_response(question_text)
            output["question"] = question

        # 상태 동기화 (messages 제외 — LangChain 메시지 객체는 dict에 누적하면 문제)
        safe_keys = {
            "current_situation", "previous_situation", "heart_rate",
            "visible_people", "current_emotion", "previous_emotion",
            "current_chapter", "chapters_today", "beats_today",
            "conversation_history", "pending_question", "question_count_today",
            "expert_request", "expert_result", "last_beat_time",
            "last_question_time", "day_date", "next_action", "user_speech",
        }
        for k in safe_keys:
            if k in result:
                storyteller_state[k] = result[k]

        return output

    except Exception as e:
        log.error(f"Storyteller graph error: {e}")
        return {"action": "error", "error": str(e)}


@app.post("/v2/expert/delegate")
async def delegate_expert(req: ExpertRequest):
    """단일 전문가에게 작업 위임 — CrewAI"""
    if crew_manager is None:
        raise HTTPException(503, "전문가 팀 미초기화")
    result = await crew_manager.delegate(
        domain=req.domain,
        query=req.query,
        context=req.context,
    )
    return result


@app.post("/v2/expert/team")
async def delegate_team(req: TeamRequest):
    """복수 전문가에게 동시 위임 — CrewAI 병렬"""
    if crew_manager is None:
        raise HTTPException(503, "전문가 팀 미초기화")
    results = await crew_manager.delegate_team(
        domains=req.domains,
        query=req.query,
        context=req.context,
    )
    return {"results": results, "count": len(results)}


@app.get("/v2/expert/list")
async def list_experts():
    """사용 가능한 전문가 목록"""
    if crew_manager is None:
        raise HTTPException(503, "전문가 팀 미초기화")
    return {"experts": crew_manager.list_experts()}


@app.get("/v2/llm/status")
async def llm_pool_status():
    """LLM 풀 상태 — 서버별 헬스/레이턴시/에러율"""
    if llm_pool is None:
        raise HTTPException(503, "LLM 풀 미초기화")
    return llm_pool.get_status()


@app.post("/v2/llm/chat")
async def llm_chat(prompt: str, system: str = "", max_tokens: int = 1024, tier: str = None):
    """직접 LLM 채팅 (풀 자동 라우팅, 디버그/테스트용)"""
    prefer = {"high": ServerTier.HIGH, "medium": ServerTier.MEDIUM, "low": ServerTier.LOW}.get(tier)
    try:
        response = await llm_pool.chat(prompt=prompt, system=system, max_tokens=max_tokens, prefer_tier=prefer)
        return {
            "text": response.text,
            "server": response.server_name,
            "model": response.model,
            "latency_ms": response.latency_ms,
            "fallback_used": response.fallback_used,
        }
    except RuntimeError as e:
        raise HTTPException(502, str(e))


@app.get("/v2/storyteller/state")
async def get_storyteller_state():
    """이야기꾼 현재 상태 (디버그용)"""
    if storyteller_state is None:
        return {"status": "not_initialized"}
    return {
        "day_date": storyteller_state.get("day_date"),
        "current_situation": storyteller_state.get("current_situation"),
        "current_chapter": storyteller_state.get("current_chapter"),
        "beats_today": len(storyteller_state.get("beats_today", [])),
        "questions_today": storyteller_state.get("question_count_today", 0),
        "last_beat_time": storyteller_state.get("last_beat_time", 0),
        "last_question_time": storyteller_state.get("last_question_time", 0),
    }


# ─── v2 Debate 엔드포인트 ───

class DebateRequest(BaseModel):
    """전문가 토론 요청"""
    topic: str  # 토론 주제
    domains: list[str]  # 참여 전문가 (2~4명)
    context: Optional[dict] = None
    max_rounds: int = 3  # 1=독립분석, 2=+비판, 3=+합의


@app.post("/v2/debate")
async def run_debate(req: DebateRequest):
    """Multi-Agent Debate — 전문가 토론 + 합의 도출

    중요한 결정에서 2~4명 전문가가 라운드 기반으로 토론.
    Round 1: 독립 분석 (병렬)
    Round 2: 상호 비판 (병렬)
    Round 3: 중재자 합의 도출
    """
    if debate_manager is None:
        raise HTTPException(503, "토론 시스템 미초기화")
    if len(req.domains) < 2:
        raise HTTPException(400, "최소 2명의 전문가 필요")
    if len(req.domains) > 4:
        raise HTTPException(400, "최대 4명의 전문가까지 지원")

    result = await debate_manager.run_debate(
        topic=req.topic,
        domains=req.domains,
        context=req.context,
        max_rounds=req.max_rounds,
    )

    return {
        "topic": result.topic,
        "domains": result.domains,
        "consensus_level": result.consensus_level,
        "summary": result.summary,
        "action_plan": result.action_plan,
        "dissent": result.dissent,
        "total_rounds": result.total_rounds,
        "latency_ms": result.latency_ms,
        "rounds": [
            {
                "round": r.round_num,
                "expert": r.expert_domain,
                "role": r.expert_role,
                "content": r.content,
            }
            for r in result.rounds
        ],
    }


# ═══════════════════════════════════════════
# Internal
# ═══════════════════════════════════════════

def _add_episode(event: EpisodeEvent):
    ep_dict = event.model_dump()
    episode_buffer.append(ep_dict)
    if len(episode_buffer) > MAX_EPISODE_BUFFER:
        episode_buffer.pop(0)

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


def _build_context_summary(events: list[dict]) -> str:
    if not events:
        return "컨텍스트 없음"
    parts = []
    for e in events[-5:]:
        ts = datetime.fromtimestamp(e.get("timestamp", 0)).strftime("%H:%M:%S")
        parts.append(f"[{ts} {e.get('event_type', '?')}] {e.get('content', '')[:100]}")
    return "\n".join(parts)


async def _storyteller_fallback(req: StorytellerInput) -> dict:
    """LangGraph 없을 때 직접 LLM 호출 폴백"""
    if llm_pool is None:
        return {"action": "error", "error": "LLM 풀 미초기화"}

    prompt = f"현재 상황: {req.situation}\n"
    if req.episodes:
        prompt += "최근 관찰:\n"
        for ep in req.episodes[-3:]:
            prompt += f"- {ep.get('content', '')[:100]}\n"
    if req.user_speech:
        prompt += f"사용자 발화: {req.user_speech}\n"
    prompt += "\n지금 이 순간을 3인칭 내러티브로 기록하세요. 1~3문장."

    try:
        response = await llm_pool.chat(
            prompt=prompt,
            system="당신은 이야기꾼입니다. 사용자의 하루를 3인칭 내러티브로 기록합니다. 한국어로 작성.",
            max_tokens=256,
        )
        return {
            "action": "narrate",
            "narrative_beat": {
                "timestamp": datetime.now().isoformat(),
                "narrative": response.text,
                "tone": "calm",
                "trigger": "fallback",
                "chapter": req.situation,
            },
            "server": response.server_name,
        }
    except Exception as e:
        return {"action": "error", "error": str(e)}


# ═══════════════════════════════════════════
# Entry Point
# ═══════════════════════════════════════════

if __name__ == "__main__":
    uvicorn.run(
        "orchestrator_v2:app",
        host=HOST,
        port=PORT,
        reload=False,
        log_level="info",
    )
