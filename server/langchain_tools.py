#!/usr/bin/env python3
"""
LangChain Tools — 기존 서비스를 LangChain 도구로 래핑.

이 도구들은 LangGraph 노드 / CrewAI 에이전트에서 사용됨.
기존 mem0_service, episode_store, prediction_engine 등을 재사용.
"""

import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Optional

from langchain_core.tools import tool

log = logging.getLogger("langchain_tools")

# ─── 전역 서비스 참조 (orchestrator_v2.py에서 초기화) ───
_mem0_svc = None
_episode_db = None
_card_store = None
_predictor = None
_episode_buffer = None  # list[dict] 참조


def init_tools(mem0_svc, episode_db, card_store, predictor, episode_buffer):
    """도구에 필요한 서비스 인스턴스 주입 (orchestrator_v2 lifespan에서 호출)"""
    global _mem0_svc, _episode_db, _card_store, _predictor, _episode_buffer
    _mem0_svc = mem0_svc
    _episode_db = episode_db
    _card_store = card_store
    _predictor = predictor
    _episode_buffer = episode_buffer


# ═══════════════════════════════════════════
# 기억 도구 (Mem0)
# ═══════════════════════════════════════════

@tool(parse_docstring=True)
def search_memory(query: str, limit: int = 5) -> str:
    """사용자의 과거 기억/사실을 검색합니다. 특정 사람, 장소, 이벤트에 대한 기억을 찾을 때 사용하세요.

    Args:
        query: 검색할 내용 (예: "어제 수업", "김민수 학생", "점심 메뉴")
        limit: 최대 결과 수
    """
    if _mem0_svc is None:
        return "기억 시스템 미초기화"
    try:
        results = _mem0_svc.search(query=query, user_id="teacher", limit=limit)
        if not results:
            return f"'{query}'에 대한 기억이 없습니다."
        formatted = []
        for r in results:
            memory = r.get("memory", r.get("text", ""))
            formatted.append(f"- {memory}")
        return "\n".join(formatted)
    except Exception as e:
        return f"기억 검색 오류: {e}"


@tool(parse_docstring=True)
def save_memory(content: str, event_type: str = "observation") -> str:
    """새로운 사실/관찰을 장기 기억에 저장합니다. 중요한 대화, 관찰, 결정을 기록할 때 사용하세요.

    Args:
        content: 저장할 내용
        event_type: 유형 (observation, speech, interaction, reflection)
    """
    if _mem0_svc is None:
        return "기억 시스템 미초기화"
    try:
        result = _mem0_svc.add_episode(
            event_type=event_type,
            content=content,
            user_id="teacher",
        )
        facts = len(result.get("results", []))
        return f"기억 저장 완료 (추출된 사실: {facts}개)"
    except Exception as e:
        return f"기억 저장 오류: {e}"


# ═══════════════════════════════════════════
# 에피소드 도구
# ═══════════════════════════════════════════

@tool(parse_docstring=True)
def get_recent_episodes(limit: int = 10) -> str:
    """최근 에피소드(비전/음성/센서 이벤트)를 조회합니다. 현재 상황을 파악할 때 사용하세요.

    Args:
        limit: 조회할 최근 에피소드 수
    """
    if _episode_buffer is None:
        return "에피소드 버퍼 미초기화"
    recent = _episode_buffer[-limit:]
    if not recent:
        return "최근 에피소드 없음"
    lines = []
    for ep in recent:
        ts = datetime.fromtimestamp(ep.get("timestamp", 0)).strftime("%H:%M")
        lines.append(f"[{ts} {ep.get('event_type', '?')}] {ep.get('content', '')[:150]}")
    return "\n".join(lines)


@tool(parse_docstring=True)
def get_episode_history(date: Optional[str] = None, limit: int = 20) -> str:
    """과거 에피소드 이력을 조회합니다. 특정 날짜의 기록을 확인할 때 사용하세요.

    Args:
        date: 조회 날짜 (YYYY-MM-DD). None이면 최근 기록.
        limit: 최대 결과 수
    """
    if _episode_db is None:
        return "에피소드 DB 미초기화"
    try:
        if date:
            episodes = _episode_db.get_by_date(date)
        else:
            episodes = _episode_db.get_recent(limit)
        if not episodes:
            return f"{'날짜 ' + date + '의' if date else '최근'} 에피소드 없음"
        lines = [f"[{ep.get('timestamp', '?')} {ep.get('event_type', '?')}] {ep.get('content', '')[:100]}"
                 for ep in episodes[:limit]]
        return f"총 {len(episodes)}건:\n" + "\n".join(lines)
    except Exception as e:
        return f"에피소드 조회 오류: {e}"


# ═══════════════════════════════════════════
# 시맨틱 카드 도구 (L3 하이브리드 검색)
# ═══════════════════════════════════════════

@tool(parse_docstring=True)
def search_semantic_cards(
    query: str,
    person: Optional[str] = None,
    location: Optional[str] = None,
    emotion: Optional[str] = None,
    date: Optional[str] = None,
    n_results: int = 5,
) -> str:
    """시맨틱 카드를 하이브리드 검색합니다. 벡터 유사도 + 메타데이터 필터를 결합합니다.
    특정 사람/장소/감정/날짜 조건으로 정밀 검색할 때 사용하세요.

    Args:
        query: 검색 쿼리
        person: 관련 인물 필터
        location: 장소 필터
        emotion: 감정 필터 (positive/negative/neutral/mixed)
        date: 날짜 필터 (YYYY-MM-DD)
        n_results: 최대 결과 수
    """
    if _card_store is None:
        return "시맨틱 카드 스토어 미초기화"
    try:
        results = _card_store.search(
            query=query, n_results=n_results,
            person=person, location=location, emotion=emotion, date=date,
        )
        if not results:
            return "검색 결과 없음"
        lines = []
        for r in results:
            meta = r.get("metadata", {})
            lines.append(
                f"- [{meta.get('date', '?')}] {r.get('content', '')[:120]} "
                f"(관련도: {r.get('distance', 0):.2f})"
            )
        return "\n".join(lines)
    except Exception as e:
        return f"카드 검색 오류: {e}"


# ═══════════════════════════════════════════
# 예측 도구
# ═══════════════════════════════════════════

@tool(parse_docstring=True)
def get_predictions() -> str:
    """오늘의 행동 예측을 조회합니다. 회복 상태, 최적 페이스, 훈련 부하, 수면 품질 등.
    사용자의 컨디션을 파악하거나 운동 계획을 세울 때 사용하세요.
    """
    if _predictor is None:
        return "예측 엔진 미초기화"
    try:
        daily = _predictor.build_daily_predictions()
        parts = []
        for k, v in daily.items():
            parts.append(f"- {k}: {v}")
        return "오늘의 예측:\n" + "\n".join(parts)
    except Exception as e:
        return f"예측 조회 오류: {e}"


@tool(parse_docstring=True)
def get_forecast_report() -> str:
    """NeuralProphet/TFT 기반 행동 예측 리포트를 조회합니다.
    향후 며칠간의 활동 패턴 예측, 이상 징후 등을 확인할 때 사용하세요.
    """
    try:
        forecast_dir = Path("data/forecasts")
        files = sorted(forecast_dir.glob("forecast_*.json"), reverse=True)
        if not files:
            return "예측 리포트 없음 (아직 생성되지 않음)"
        report = json.loads(files[0].read_text(encoding="utf-8"))
        insights = report.get("insights", [])
        if not insights:
            return "예측 인사이트 없음"
        return "예측 인사이트:\n" + "\n".join(f"- {i}" for i in insights[:10])
    except Exception as e:
        return f"예측 리포트 조회 오류: {e}"


# ═══════════════════════════════════════════
# 마이닝 인사이트 도구
# ═══════════════════════════════════════════

@tool(parse_docstring=True)
def get_mining_insights(category: str = "all") -> str:
    """마이닝 인사이트를 조회합니다. 토픽 분석, 시계열 패턴, 지식 그래프 결과를 확인할 때 사용하세요.

    Args:
        category: 카테고리 (topic/timeseries/kg/all)
    """
    sources = {
        "topic": "data/topic_reports/topic_report_*.json",
        "timeseries": "data/timeseries_reports/timeseries_*.json",
        "kg": "data/kg_reports/kg_report_*.json",
    }

    if category != "all":
        sources = {category: sources.get(category, "")}

    parts = []
    for name, pattern in sources.items():
        try:
            files = sorted(Path(".").glob(pattern), reverse=True)
            if files:
                report = json.loads(files[0].read_text(encoding="utf-8"))
                insights = report.get("insights", [])
                if insights:
                    parts.append(f"## {name}")
                    for i in insights[:5]:
                        parts.append(f"  - {i}")
        except Exception:
            pass

    return "\n".join(parts) if parts else f"'{category}' 마이닝 인사이트 없음"


@tool(parse_docstring=True)
def get_morning_briefing() -> str:
    """오늘의 아침 브리핑을 조회합니다. 야간 반성 + 마이닝 결과의 종합 요약입니다."""
    try:
        briefing_dir = Path("data/briefings")
        files = sorted(briefing_dir.glob("briefing_*.md"), reverse=True)
        if not files:
            return "아침 브리핑 없음"
        return files[0].read_text(encoding="utf-8")[:2000]
    except Exception as e:
        return f"브리핑 조회 오류: {e}"


# ═══════════════════════════════════════════
# 학생/인물 도구
# ═══════════════════════════════════════════

@tool(parse_docstring=True)
def search_person_history(person_name: str) -> str:
    """특정 인물에 대한 모든 기록을 검색합니다. 학생 이름으로 과거 기록, 행동 이력, 관계 정보를 조회합니다.

    Args:
        person_name: 검색할 인물 이름
    """
    results = []

    # 1. Mem0에서 인물 관련 기억
    if _mem0_svc:
        try:
            memories = _mem0_svc.search(query=person_name, user_id="teacher", limit=5)
            for m in memories:
                results.append(f"[기억] {m.get('memory', m.get('text', ''))}")
        except Exception:
            pass

    # 2. 시맨틱 카드에서 인물 필터
    if _card_store:
        try:
            cards = _card_store.search(query=person_name, person=person_name, n_results=5)
            for c in cards:
                results.append(f"[카드] {c.get('content', '')[:120]}")
        except Exception:
            pass

    if not results:
        return f"'{person_name}'에 대한 기록이 없습니다."

    return f"{person_name} 관련 기록 ({len(results)}건):\n" + "\n".join(results)


# ═══════════════════════════════════════════
# 도구 모음 (CrewAI / LangGraph에서 사용)
# ═══════════════════════════════════════════

def get_all_tools() -> list:
    """모든 도구 목록 반환"""
    return [
        search_memory,
        save_memory,
        get_recent_episodes,
        get_episode_history,
        search_semantic_cards,
        get_predictions,
        get_forecast_report,
        get_mining_insights,
        get_morning_briefing,
        search_person_history,
    ]


def get_storyteller_tools() -> list:
    """이야기꾼 전용 도구"""
    return [
        search_memory,
        save_memory,
        get_recent_episodes,
        search_semantic_cards,
        get_predictions,
        get_morning_briefing,
    ]


def get_expert_tools() -> list:
    """전문가 팀 전용 도구"""
    return [
        search_memory,
        search_person_history,
        get_episode_history,
        search_semantic_cards,
        get_mining_insights,
    ]
