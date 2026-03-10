#!/usr/bin/env python3
"""
XREAL Nightly Reflection — 야간 자기 반성 배치

매일 새벽 3시 실행. 하루치 에피소드 로그를 장기 맥락 LLM에 넘겨
자기 반성 + 패턴 발견 + 인사이트 추출 → Mem0에 저장.

순서:
  1. Orchestrator에서 최근 에피소드 전체 수집
  2. Mem0에서 기존 기억 조회 (맥락)
  3. Long-context LLM에 하루 로그 + 기존 기억 전달 → 반성문 생성
  4. 반성문에서 사실 추출 → Mem0에 저장 (reflection 타입)
  5. 내일 아침 브리핑용 요약 생성 → 파일 저장

Usage:
    python nightly_reflection.py              # 즉시 실행
    python nightly_reflection.py --schedule   # 새벽 3시 스케줄

Cron:
    0 3 * * * cd /path/to/server && python nightly_reflection.py >> logs/reflection.log 2>&1
"""

import os
import sys
import json
import time
import logging
import argparse
from datetime import datetime, timedelta
from pathlib import Path

import httpx

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("logs/nightly_reflection.log", encoding="utf-8"),
    ],
)
log = logging.getLogger("nightly_reflection")

# ─── Config ───

ORCHESTRATOR_URL = os.getenv("ORCHESTRATOR_URL", "http://127.0.0.1:8091")
LLM_URL = os.getenv("REFLECTION_LLM_URL", "http://127.0.0.1:8080")
LLM_MODEL = os.getenv("REFLECTION_LLM_MODEL", "minicpm-v")
BRIEFING_DIR = Path(os.getenv("BRIEFING_DIR", "./data/briefings"))
REFLECTION_DIR = Path(os.getenv("REFLECTION_DIR", "./data/reflections"))

# 반성 프롬프트 (한국어)
REFLECTION_SYSTEM_PROMPT = """당신은 AR 안경 기반 AI 동반자 '이야기꾼'의 자기 반성 모듈입니다.
하루 동안 수집된 에피소드와 기존 기억을 분석하여 다음을 수행하세요:

1. **하루 요약**: 주요 활동, 장소, 만난 사람, 핵심 대화 3줄 요약
2. **패턴 발견**: 반복되는 행동, 시간대별 루틴, 감정 변화 패턴
3. **인사이트**: 새로 알게 된 사실, 사용자의 선호/습관 변화
4. **자기 반성**: 오늘 잘한 점 (유용했던 개입), 놓친 기회 (도움을 줄 수 있었던 순간)
5. **내일 제안**: 내일 아침 브리핑에 포함할 알림, 준비사항, 제안

출력 형식은 JSON:
{
    "date": "YYYY-MM-DD",
    "daily_summary": "...",
    "patterns": ["패턴1", "패턴2"],
    "insights": ["인사이트1", "인사이트2"],
    "self_reflection": {
        "good": ["잘한 점1"],
        "missed": ["놓친 기회1"]
    },
    "tomorrow_briefing": {
        "reminders": ["알림1"],
        "suggestions": ["제안1"]
    },
    "key_facts": ["Mem0에 저장할 핵심 사실1", "핵심 사실2"]
}
"""

BRIEFING_TEMPLATE = """# 아침 브리핑 — {date}

## 어제 요약
{daily_summary}

## 발견된 패턴
{patterns}

## 새로운 인사이트
{insights}

## 오늘의 알림
{reminders}

## 제안
{suggestions}
"""


def ensure_dirs():
    """필요 디렉토리 생성"""
    BRIEFING_DIR.mkdir(parents=True, exist_ok=True)
    REFLECTION_DIR.mkdir(parents=True, exist_ok=True)
    Path("logs").mkdir(exist_ok=True)


def fetch_episodes(client: httpx.Client, limit: int = 50) -> list[dict]:
    """Orchestrator에서 에피소드 수집 (L2 이력 우선, 폴백으로 L1 버퍼)"""
    # 어제 날짜의 에피소드를 L2에서 조회
    yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")
    try:
        r = client.get(f"{ORCHESTRATOR_URL}/v1/episode/history", params={"date": yesterday})
        r.raise_for_status()
        episodes = r.json().get("episodes", [])
        if episodes:
            log.info(f"L2 에피소드 {len(episodes)}개 수집 ({yesterday})")
            return episodes
    except Exception as e:
        log.warning(f"L2 에피소드 조회 실패, L1 폴백: {e}")

    # L1 워킹 메모리 폴백
    try:
        r = client.get(f"{ORCHESTRATOR_URL}/v1/episode/recent", params={"limit": limit})
        r.raise_for_status()
        episodes = r.json().get("episodes", [])
        log.info(f"L1 에피소드 {len(episodes)}개 수집 (폴백)")
        return episodes
    except Exception as e:
        log.error(f"에피소드 수집 실패: {e}")
        return []


def fetch_existing_memories(client: httpx.Client, user_id: str = "teacher") -> list[dict]:
    """Mem0에서 기존 기억 조회 (맥락 제공용)"""
    try:
        r = client.get(f"{ORCHESTRATOR_URL}/v1/mem0/all", params={"user_id": user_id})
        r.raise_for_status()
        results = r.json().get("results", [])
        log.info(f"기존 기억 {len(results)}개 조회")
        return results
    except Exception as e:
        log.warning(f"기존 기억 조회 실패: {e}")
        return []


def build_reflection_prompt(episodes: list[dict], memories: list[dict]) -> str:
    """반성용 프롬프트 구성"""
    today = datetime.now().strftime("%Y-%m-%d")

    # 에피소드 포맷팅
    episode_lines = []
    for ep in episodes:
        ts = datetime.fromtimestamp(ep.get("timestamp", 0)).strftime("%H:%M:%S")
        etype = ep.get("event_type", "unknown")
        content = ep.get("content", "")[:500]
        episode_lines.append(f"[{ts} {etype}] {content}")

    episodes_text = "\n".join(episode_lines) if episode_lines else "(에피소드 없음)"

    # 기존 기억 포맷팅
    memory_lines = []
    for mem in memories[-20:]:  # 최근 20개만
        text = mem.get("memory", "")
        memory_lines.append(f"- {text}")

    memories_text = "\n".join(memory_lines) if memory_lines else "(기존 기억 없음)"

    return f"""오늘 날짜: {today}

## 오늘의 에피소드 로그 ({len(episodes)}건)
{episodes_text}

## 기존에 알고 있는 사실 ({len(memories)}건)
{memories_text}

위 정보를 바탕으로 하루 반성을 수행하세요."""


def call_llm(client: httpx.Client, user_prompt: str) -> str:
    """LLM 호출 (OpenAI-compatible API)"""
    payload = {
        "model": LLM_MODEL,
        "messages": [
            {"role": "system", "content": REFLECTION_SYSTEM_PROMPT},
            {"role": "user", "content": user_prompt},
        ],
        "max_tokens": 2048,
        "temperature": 0.3,
    }

    try:
        r = client.post(
            f"{LLM_URL}/v1/chat/completions",
            json=payload,
            timeout=120.0,  # 반성은 오래 걸릴 수 있음
        )
        r.raise_for_status()
        data = r.json()
        text = data["choices"][0]["message"]["content"]
        log.info(f"LLM 반성 생성 완료 ({len(text)}자)")
        return text
    except Exception as e:
        log.error(f"LLM 호출 실패: {e}")
        return ""


def parse_reflection(raw_text: str) -> dict:
    """LLM 출력에서 JSON 추출 (코드블록 처리)"""
    text = raw_text.strip()

    # ```json ... ``` 블록 추출
    if "```json" in text:
        start = text.index("```json") + 7
        end = text.index("```", start)
        text = text[start:end].strip()
    elif "```" in text:
        start = text.index("```") + 3
        end = text.index("```", start)
        text = text[start:end].strip()

    try:
        return json.loads(text)
    except json.JSONDecodeError as e:
        log.warning(f"JSON 파싱 실패, 원문 저장: {e}")
        return {
            "date": datetime.now().strftime("%Y-%m-%d"),
            "daily_summary": raw_text[:500],
            "patterns": [],
            "insights": [],
            "self_reflection": {"good": [], "missed": []},
            "tomorrow_briefing": {"reminders": [], "suggestions": []},
            "key_facts": [],
            "_raw": raw_text,
        }


def save_to_mem0(client: httpx.Client, reflection: dict):
    """반성 결과에서 추출한 핵심 사실을 Mem0에 저장"""
    key_facts = reflection.get("key_facts", [])
    saved = 0

    for fact in key_facts:
        try:
            r = client.post(
                f"{ORCHESTRATOR_URL}/v1/mem0/add",
                json={
                    "content": fact,
                    "user_id": "teacher",
                    "event_type": "reflection",
                    "extra_context": f"야간 반성 ({reflection.get('date', 'unknown')})",
                },
            )
            r.raise_for_status()
            saved += 1
        except Exception as e:
            log.warning(f"Mem0 저장 실패: {fact[:50]}... — {e}")

    # 패턴도 저장
    patterns = reflection.get("patterns", [])
    for pattern in patterns:
        try:
            client.post(
                f"{ORCHESTRATOR_URL}/v1/mem0/add",
                json={
                    "content": f"[패턴] {pattern}",
                    "user_id": "teacher",
                    "event_type": "reflection",
                },
            )
            saved += 1
        except Exception:
            pass

    log.info(f"Mem0에 {saved}건 저장 (사실 {len(key_facts)}건 + 패턴 {len(patterns)}건)")
    return saved


def save_briefing(reflection: dict):
    """내일 아침 브리핑 파일 저장"""
    date = reflection.get("date", datetime.now().strftime("%Y-%m-%d"))
    tomorrow = (datetime.strptime(date, "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")

    patterns_text = "\n".join(f"- {p}" for p in reflection.get("patterns", [])) or "- 없음"
    insights_text = "\n".join(f"- {i}" for i in reflection.get("insights", [])) or "- 없음"
    reminders_text = "\n".join(
        f"- {r}" for r in reflection.get("tomorrow_briefing", {}).get("reminders", [])
    ) or "- 없음"
    suggestions_text = "\n".join(
        f"- {s}" for s in reflection.get("tomorrow_briefing", {}).get("suggestions", [])
    ) or "- 없음"

    briefing = BRIEFING_TEMPLATE.format(
        date=tomorrow,
        daily_summary=reflection.get("daily_summary", "요약 없음"),
        patterns=patterns_text,
        insights=insights_text,
        reminders=reminders_text,
        suggestions=suggestions_text,
    )

    path = BRIEFING_DIR / f"briefing_{tomorrow}.md"
    path.write_text(briefing, encoding="utf-8")
    log.info(f"브리핑 저장: {path}")
    return path


def save_reflection_log(reflection: dict, raw_text: str):
    """반성 원본 + 파싱 결과 저장"""
    date = reflection.get("date", datetime.now().strftime("%Y-%m-%d"))
    path = REFLECTION_DIR / f"reflection_{date}.json"

    data = {
        "reflection": reflection,
        "raw_llm_output": raw_text,
        "generated_at": datetime.now().isoformat(),
    }
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    log.info(f"반성 로그 저장: {path}")
    return path


def run_reflection():
    """메인 반성 루프"""
    log.info("=" * 60)
    log.info(f"야간 반성 시작: {datetime.now().isoformat()}")
    log.info("=" * 60)

    ensure_dirs()

    with httpx.Client(timeout=httpx.Timeout(30.0, connect=5.0)) as client:
        # 1. 에피소드 수집
        episodes = fetch_episodes(client, limit=50)
        if not episodes:
            log.warning("에피소드 없음 — 에피소드 없이 기존 기억만으로 반성 시도")

        # 2. 기존 기억 조회
        memories = fetch_existing_memories(client)

        # 3. 에피소드도 없고 기억도 없으면 스킵
        if not episodes and not memories:
            log.info("에피소드도 기억도 없음 — 반성 스킵")
            return

        # 4. 반성 프롬프트 생성
        prompt = build_reflection_prompt(episodes, memories)
        log.info(f"프롬프트 생성 ({len(prompt)}자)")

        # 5. LLM 호출
        raw_text = call_llm(client, prompt)
        if not raw_text:
            log.error("LLM 응답 없음 — 반성 중단")
            return

        # 6. 결과 파싱
        reflection = parse_reflection(raw_text)
        log.info(f"반성 파싱 완료: 패턴 {len(reflection.get('patterns', []))}건, "
                 f"인사이트 {len(reflection.get('insights', []))}건, "
                 f"핵심사실 {len(reflection.get('key_facts', []))}건")

        # 7. Mem0에 저장
        saved = save_to_mem0(client, reflection)

        # 8. L1 → L3 플러시 (에피소드 → Mem0)
        if episodes:
            try:
                r = client.post(
                    f"{ORCHESTRATOR_URL}/v1/episode/flush-to-mem0",
                    params={"limit": len(episodes)},
                )
                r.raise_for_status()
                flush_result = r.json()
                log.info(f"에피소드 → Mem0 플러시: {flush_result.get('flushed', 0)}건")
            except Exception as e:
                log.warning(f"에피소드 플러시 실패: {e}")

        # 9. 브리핑 저장
        briefing_path = save_briefing(reflection)

        # 10. 반성 로그 저장
        reflection_path = save_reflection_log(reflection, raw_text)

    log.info("=" * 60)
    log.info(f"야간 반성 완료: Mem0 {saved}건, 브리핑 {briefing_path}")
    log.info("=" * 60)


def schedule_nightly():
    """간단한 스케줄러 (새벽 3시)"""
    import sched
    import time as time_module

    scheduler = sched.scheduler(time_module.time, time_module.sleep)

    def next_3am() -> float:
        now = datetime.now()
        target = now.replace(hour=3, minute=0, second=0, microsecond=0)
        if now >= target:
            target += timedelta(days=1)
        return (target - now).total_seconds()

    def scheduled_run():
        try:
            run_reflection()
        except Exception as e:
            log.error(f"반성 실행 에러: {e}", exc_info=True)
        # 다음 실행 예약
        delay = next_3am()
        scheduler.enter(delay, 1, scheduled_run)
        log.info(f"다음 반성: {delay / 3600:.1f}시간 후")

    delay = next_3am()
    log.info(f"야간 반성 스케줄러 시작. 첫 실행: {delay / 3600:.1f}시간 후")
    scheduler.enter(delay, 1, scheduled_run)
    scheduler.run()


# ─── Main ───

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="XREAL Nightly Reflection")
    parser.add_argument("--schedule", action="store_true", help="새벽 3시 스케줄러 모드")
    args = parser.parse_args()

    if args.schedule:
        schedule_nightly()
    else:
        run_reflection()
