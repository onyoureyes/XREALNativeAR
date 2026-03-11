#!/usr/bin/env python3
"""
Storyteller LangGraph — 이야기꾼 상태 그래프.

Android StorytellerOrchestrator의 서버 버전.
LangGraph StateGraph로 구현하여 체크포인트 + 재시도 + 인간 개입 지원.

상태 전이:
    IDLE → OBSERVE → NARRATE → REFLECT → ASK_QUESTION → DELEGATE_EXPERT
         ↗ (새 에피소드)     ↘ (주기적)     ↘ (조건부)      ↘ (필요시)
"""

import logging
import time
from datetime import datetime
from typing import Annotated, Literal, Optional
from operator import add

from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages

from langchain_core.messages import HumanMessage, SystemMessage, AIMessage

log = logging.getLogger("storyteller_graph")


# ═══════════════════════════════════════════
# 상태 정의
# ═══════════════════════════════════════════

from typing import TypedDict


class NarrativeBeat(TypedDict):
    """내러티브 기록 하나"""
    timestamp: str
    narrative: str
    tone: str        # reflective/joyful/curious/calm/tense/warm/melancholic
    trigger: str     # reflection/transition/conversation/expert/emotional_shift
    chapter: str


class Chapter(TypedDict):
    """이야기 챕터"""
    title: str
    situation: str
    started_at: str
    beats: list[NarrativeBeat]


class StorytellerState(TypedDict):
    """이야기꾼 전체 상태 — LangGraph StateGraph의 state"""
    # 입력
    episodes: list[dict]            # 최근 에피소드 (L1)
    current_situation: str          # 현재 상황 (LifeSituation)
    previous_situation: str         # 이전 상황
    heart_rate: Optional[float]     # 현재 심박
    visible_people: list[str]      # 시야 내 사람들
    current_emotion: Optional[str]  # 현재 감정
    previous_emotion: Optional[str] # 이전 감정
    user_speech: Optional[str]      # 사용자 발화 (있으면)

    # 내러티브 상태
    current_chapter: Optional[Chapter]
    chapters_today: list[Chapter]
    beats_today: list[NarrativeBeat]

    # 대화 상태
    conversation_history: list[dict]  # [{speaker, text, timestamp}]
    pending_question: Optional[str]
    question_count_today: int

    # 전문가 위임
    expert_request: Optional[dict]  # {domain, query, context}
    expert_result: Optional[dict]   # {expert_name, insight, action}

    # 메타
    last_beat_time: float
    last_question_time: float
    day_date: str

    # LLM 출력
    messages: Annotated[list, add_messages]
    next_action: str  # "observe" | "narrate" | "ask" | "delegate" | "end"


# ═══════════════════════════════════════════
# 시스템 프롬프트
# ═══════════════════════════════════════════

STORYTELLER_SYSTEM_TEMPLATE = """당신은 '이야기꾼(Storyteller)'입니다.
사용자의 하루를 따뜻하고 통찰력 있는 3인칭 내러티브로 기록합니다.

{user_profile}

## 규칙
- 3인칭 관찰자 시점 ("그는", "그녀는")
- 1~3문장으로 간결하게
- 감각적 묘사 포함 (빛, 소리, 분위기)
- 감정은 관찰에서 유추 (심박수, 표정, 목소리 톤)
- 판단하지 않고 기록만
- 반복 표현 회피 (이전 beat 참고)
- 사용자의 실제 환경과 직업에 맞는 배경 묘사 (배경지식 참고)
- 사용자가 말한 내용이 있으면 반드시 내러티브에 반영
- 한국어로 작성

## 출력 형식
narrative: (내러티브 텍스트)
tone: (감정 톤 — reflective/joyful/curious/calm/tense/warm/melancholic 중 택1)"""

# 하위 호환: 프로필 없을 때 기본 시스템 프롬프트
STORYTELLER_SYSTEM = STORYTELLER_SYSTEM_TEMPLATE.format(user_profile="")

QUESTION_SYSTEM_TEMPLATE = """당신은 '이야기꾼'입니다. 사용자에게 자연스러운 질문을 합니다.

{user_profile}

## 규칙
- 현재 상황과 맥락에 맞는 질문
- 부담스럽지 않은 톤 (친근하게, 존댓말)
- 열린 질문 (예/아니오 대신 생각을 이끌어내는)
- 최근 대화 맥락을 고려해서 반복하지 않기
- 배경지식을 활용하여 개인화된 질문
- 한국어로 작성
- 한 번에 하나의 질문만

## 출력 형식
question: (질문 텍스트)"""

QUESTION_SYSTEM = QUESTION_SYSTEM_TEMPLATE.format(user_profile="")


# ═══════════════════════════════════════════
# 그래프 노드
# ═══════════════════════════════════════════

def observe(state: StorytellerState) -> dict:
    """현재 컨텍스트를 관찰하고 다음 행동 결정"""
    now = time.time()
    last_beat = state.get("last_beat_time", 0)
    last_question = state.get("last_question_time", 0)
    situation = state.get("current_situation", "UNKNOWN")
    prev_situation = state.get("previous_situation", "UNKNOWN")
    user_speech = state.get("user_speech")
    episodes = state.get("episodes", [])

    # 상황 전환 감지 → 즉시 내러티브
    if situation != prev_situation and prev_situation != "UNKNOWN":
        return {"next_action": "narrate"}

    # 사용자 발화 있음 → 대화 beat
    if user_speech:
        return {"next_action": "narrate"}

    # 감정 변화 감지
    current_emotion = state.get("current_emotion")
    previous_emotion = state.get("previous_emotion")
    if current_emotion and previous_emotion and current_emotion != previous_emotion:
        return {"next_action": "narrate"}

    # 전문가 결과 대기 중
    if state.get("expert_result"):
        return {"next_action": "narrate"}

    # 5분마다 주기적 리플렉션
    if now - last_beat > 300:
        return {"next_action": "narrate"}

    # 10분마다 능동적 질문 (하루 최대 20회)
    if now - last_question > 600 and state.get("question_count_today", 0) < 20:
        # 수면/통화 중에는 질문 안 함
        suppressed = {"SLEEPING", "SLEEPING_PREP", "PHONE_CALL", "UNKNOWN"}
        if situation not in suppressed:
            return {"next_action": "ask"}

    # 새 에피소드가 5개 이상 쌓임
    if len(episodes) >= 5:
        return {"next_action": "narrate"}

    return {"next_action": "end"}


def narrate(state: StorytellerState) -> dict:
    """내러티브 beat 생성"""
    situation = state.get("current_situation", "UNKNOWN")
    prev_situation = state.get("previous_situation", "UNKNOWN")
    episodes = state.get("episodes", [])
    user_speech = state.get("user_speech")
    expert_result = state.get("expert_result")
    current_chapter = state.get("current_chapter")

    # 프롬프트 조립
    prompt_parts = []

    # 상황 전환
    if situation != prev_situation and prev_situation != "UNKNOWN":
        prompt_parts.append(f"## 상황 전환 감지\n이전: {prev_situation} → 현재: {situation}")
        trigger = "transition"
    elif user_speech:
        prompt_parts.append(f"## 대화 순간\n선생님이 말했습니다: \"{user_speech}\"")
        trigger = "conversation"
    elif expert_result:
        prompt_parts.append(
            f"## 전문가 개입\n"
            f"전문가: {expert_result.get('expert_name', '?')}\n"
            f"내용: {expert_result.get('insight', '')[:300]}"
        )
        trigger = "expert"
    elif state.get("current_emotion") != state.get("previous_emotion"):
        prompt_parts.append(
            f"## 감정 변화\n"
            f"이전: {state.get('previous_emotion', '?')} → "
            f"현재: {state.get('current_emotion', '?')}"
        )
        trigger = "emotional_shift"
    else:
        prompt_parts.append("## 주기적 관찰")
        trigger = "reflection"

    # 현재 컨텍스트
    prompt_parts.append(f"\n## 현재 컨텍스트\n상황: {situation}")
    if state.get("heart_rate"):
        prompt_parts.append(f"심박: {state['heart_rate']}bpm")
    if state.get("visible_people"):
        prompt_parts.append(f"주변 사람: {', '.join(state['visible_people'])}")
    if state.get("current_emotion"):
        prompt_parts.append(f"감정: {state['current_emotion']}")

    # 최근 에피소드
    if episodes:
        prompt_parts.append("\n## 최근 관찰")
        for ep in episodes[-5:]:
            prompt_parts.append(f"- [{ep.get('event_type', '?')}] {ep.get('content', '')[:100]}")

    # 배경지식 (Mem0 기억, 브리핑 등 — orchestrator_v2에서 주입)
    background = state.get("_background_context")
    if background:
        prompt_parts.append(f"\n## 배경지식 (사용자에 대해 알고 있는 것)\n{background}")

    # 이전 beats (반복 방지)
    beats_today = state.get("beats_today", [])
    if beats_today:
        prompt_parts.append("\n## 이전 기록 (반복 금지)")
        for b in beats_today[-3:]:
            prompt_parts.append(f"  - {b.get('narrative', '')[:80]}")

    prompt_parts.append("\n위 정보를 바탕으로 지금 이 순간을 포착하여 새로운 관찰을 기록하세요. 사용자의 실제 상황과 배경에 맞게 묘사하세요.")

    prompt = "\n".join(prompt_parts)

    # 새 챕터 시작 (상황 전환 시)
    new_chapter = current_chapter
    if situation != prev_situation and prev_situation != "UNKNOWN":
        new_chapter = Chapter(
            title=f"{situation} 시작",
            situation=situation,
            started_at=datetime.now().isoformat(),
            beats=[],
        )

    return {
        "messages": [
            SystemMessage(content=STORYTELLER_SYSTEM),
            HumanMessage(content=prompt),
        ],
        "last_beat_time": time.time(),
        "previous_situation": situation,
        "current_chapter": new_chapter or current_chapter,
        "user_speech": None,  # 소비 완료
        "expert_result": None,  # 소비 완료
        "episodes": [],  # 소비 완료
        "next_action": "end",
        # _trigger는 후처리에서 beat 생성 시 사용
        "_trigger": trigger,
    }


def ask_question(state: StorytellerState) -> dict:
    """능동적 질문 생성"""
    situation = state.get("current_situation", "UNKNOWN")
    episodes = state.get("episodes", [])
    conversation = state.get("conversation_history", [])

    prompt_parts = [f"## 현재 상황: {situation}"]

    if episodes:
        prompt_parts.append("\n## 최근 관찰")
        for ep in episodes[-3:]:
            prompt_parts.append(f"- {ep.get('content', '')[:80]}")

    if conversation:
        prompt_parts.append("\n## 최근 대화 (반복 금지)")
        for c in conversation[-4:]:
            prompt_parts.append(f"  {c.get('speaker', '?')}: {c.get('text', '')[:60]}")

    now = datetime.now()
    prompt_parts.append(f"\n현재 시각: {now.strftime('%H:%M')}")
    prompt_parts.append("상황과 맥락에 맞는 자연스러운 질문을 하나 생성하세요.")

    return {
        "messages": [
            SystemMessage(content=QUESTION_SYSTEM),
            HumanMessage(content="\n".join(prompt_parts)),
        ],
        "last_question_time": time.time(),
        "question_count_today": state.get("question_count_today", 0) + 1,
        "next_action": "end",
    }


def delegate_expert(state: StorytellerState) -> dict:
    """전문가 팀에 위임 (CrewAI 호출 트리거)

    실제 CrewAI 실행은 orchestrator_v2에서 비동기로 수행.
    여기서는 위임 요청만 생성.
    """
    request = state.get("expert_request")
    if not request:
        return {"next_action": "end"}

    log.info(f"전문가 위임 요청: {request.get('domain', '?')}")

    return {
        "expert_request": request,  # orchestrator_v2가 이 상태를 보고 CrewAI 실행
        "next_action": "end",
    }


def route_action(state: StorytellerState) -> str:
    """observe 결과에 따라 다음 노드 결정"""
    action = state.get("next_action", "end")
    if action == "narrate":
        return "narrate"
    elif action == "ask":
        return "ask_question"
    elif action == "delegate":
        return "delegate_expert"
    else:
        return END


# ═══════════════════════════════════════════
# 그래프 빌드
# ═══════════════════════════════════════════

def build_storyteller_graph():
    """이야기꾼 LangGraph 빌드 — 상태는 orchestrator_v2가 수동 관리"""
    graph = StateGraph(StorytellerState)

    # 노드 등록
    graph.add_node("observe", observe)
    graph.add_node("narrate", narrate)
    graph.add_node("ask_question", ask_question)
    graph.add_node("delegate_expert", delegate_expert)

    # 엣지
    graph.add_edge(START, "observe")
    graph.add_conditional_edges("observe", route_action)
    graph.add_edge("narrate", END)
    graph.add_edge("ask_question", END)
    graph.add_edge("delegate_expert", END)

    # checkpointer 없음 — 상태는 orchestrator_v2의 storyteller_state dict로 수동 관리.
    # MemorySaver는 인메모리이므로 서버 재시작 시 복원 불가 + 매번 새 thread_id라 의미 없음.
    return graph.compile()


def create_initial_state() -> StorytellerState:
    """초기 상태 생성"""
    return StorytellerState(
        episodes=[],
        current_situation="UNKNOWN",
        previous_situation="UNKNOWN",
        heart_rate=None,
        visible_people=[],
        current_emotion=None,
        previous_emotion=None,
        user_speech=None,
        current_chapter=None,
        chapters_today=[],
        beats_today=[],
        conversation_history=[],
        pending_question=None,
        question_count_today=0,
        expert_request=None,
        expert_result=None,
        last_beat_time=0,
        last_question_time=0,
        day_date=datetime.now().strftime("%Y-%m-%d"),
        messages=[],
        next_action="observe",
    )


def parse_narrative_response(text: str) -> tuple[str, str]:
    """AI 응답에서 narrative/tone 파싱"""
    narrative = text.strip()
    tone = "calm"

    for line in text.split("\n"):
        line = line.strip()
        if line.lower().startswith("narrative:"):
            narrative = line.split(":", 1)[1].strip()
        elif line.lower().startswith("tone:"):
            tone = line.split(":", 1)[1].strip().lower()

    return narrative, tone


def parse_question_response(text: str) -> str:
    """AI 응답에서 question 파싱"""
    for line in text.split("\n"):
        line = line.strip()
        if line.lower().startswith("question:"):
            return line.split(":", 1)[1].strip()
    return text.strip()
