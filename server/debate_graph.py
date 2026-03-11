#!/usr/bin/env python3
"""
Multi-Agent Debate Graph — LangGraph 기반 전문가 토론 시스템.

중요한 결정(위기 대응, IEP 조정, 행동 중재 등)에서
2~3명 전문가가 라운드 기반으로 서로 비판/보완 → 합의된 결론 도출.

패턴:
    Round 1: 각 전문가 독립 분석 (병렬)
    Round 2: 상호 비판 — 다른 전문가 의견 읽고 반론/보완
    Round 3: 중재자(Moderator)가 합의 도출 + 행동 계획

사용:
    debate = DebateManager(llm_pool)
    result = await debate.run_debate(
        topic="민수의 반복적 자해 행동 대응 방안",
        domains=["behavior", "health", "crisis"],
        context={...}
    )
"""

import asyncio
import logging
import time
from typing import Optional
from dataclasses import dataclass, field

log = logging.getLogger("debate_graph")


# ═══════════════════════════════════════════
# 전문가 정의 (expert_crews.py와 공유)
# ═══════════════════════════════════════════

from expert_crews import EXPERT_DEFINITIONS


MODERATOR_SYSTEM = """당신은 '토론 중재자'입니다. 전문가들의 의견을 종합하여 합의된 결론을 도출합니다.

## 규칙
- 각 전문가 의견의 핵심 논점을 파악
- 서로 충돌하는 부분은 근거 강도로 판단
- 보완적인 부분은 통합
- 실행 가능한 구체적 행동 계획 수립
- 우선순위와 담당자를 명시
- 한국어로 작성
- 합의 수준 표시 (strong_consensus / partial_consensus / no_consensus)

## 출력 형식
consensus_level: (strong_consensus / partial_consensus / no_consensus)
summary: (합의된 결론 요약, 2~3문장)
action_plan:
- (행동 1)
- (행동 2)
- (행동 3)
dissent: (소수 의견이 있다면 기록, 없으면 "없음")"""

CRITIC_TEMPLATE = """당신은 {role}입니다. {goal}

다른 전문가들의 분석을 읽고, 당신의 전문 영역에서 비판/보완하세요.

## 규칙
- 동의하는 부분은 간단히 확인
- 동의하지 않는 부분은 근거를 들어 반론
- 빠진 관점이 있으면 추가
- 구체적 대안 제시
- 3~5문장으로 간결하게
- 한국어로 작성"""


@dataclass
class DebateRound:
    """토론 라운드 하나의 결과"""
    round_num: int
    expert_domain: str
    expert_role: str
    content: str
    timestamp: float = field(default_factory=time.time)


@dataclass
class DebateResult:
    """토론 최종 결과"""
    topic: str
    domains: list[str]
    rounds: list[DebateRound]
    consensus_level: str  # strong_consensus / partial_consensus / no_consensus
    summary: str
    action_plan: list[str]
    dissent: str
    total_rounds: int
    latency_ms: int
    method: str  # "debate_graph" or "fallback_sequential"


# ═══════════════════════════════════════════
# Debate Manager
# ═══════════════════════════════════════════

class DebateManager:
    """LangGraph 기반 Multi-Agent Debate 관리자"""

    def __init__(self, llm_pool):
        self.llm_pool = llm_pool

    async def run_debate(
        self,
        topic: str,
        domains: list[str],
        context: dict = None,
        max_rounds: int = 3,
    ) -> DebateResult:
        """전문가 토론 실행

        Args:
            topic: 토론 주제
            domains: 참여 전문가 도메인 (2~4명)
            context: 추가 컨텍스트 (에피소드, 인물 정보 등)
            max_rounds: 최대 라운드 (기본 3: 독립분석 → 비판 → 합의)

        Returns:
            DebateResult — 합의 결과 + 행동 계획
        """
        t0 = time.time()

        # 유효한 도메인만 필터
        valid_domains = [d for d in domains if d in EXPERT_DEFINITIONS]
        if len(valid_domains) < 2:
            return DebateResult(
                topic=topic, domains=valid_domains, rounds=[],
                consensus_level="no_consensus",
                summary=f"토론 불가: 최소 2명의 전문가 필요 (현재 {len(valid_domains)}명)",
                action_plan=[], dissent="", total_rounds=0,
                latency_ms=0, method="error",
            )

        rounds: list[DebateRound] = []

        # ═══ Round 1: 독립 분석 (병렬) ═══
        log.info(f"토론 시작: '{topic}' — 전문가: {valid_domains}")
        round1_results = await self._round_independent(topic, valid_domains, context)
        rounds.extend(round1_results)

        # ═══ Round 2: 상호 비판 (병렬) ═══
        if max_rounds >= 2:
            round2_results = await self._round_critique(
                topic, valid_domains, round1_results, context
            )
            rounds.extend(round2_results)

        # ═══ Round 3: 중재자 합의 도출 ═══
        if max_rounds >= 3:
            consensus = await self._round_consensus(topic, rounds, valid_domains)
        else:
            # 2라운드로 끝내면 마지막 응답들을 단순 합침
            consensus = {
                "consensus_level": "partial_consensus",
                "summary": "\n".join(r.content for r in round1_results),
                "action_plan": [],
                "dissent": "없음",
            }

        latency = int((time.time() - t0) * 1000)
        log.info(f"토론 완료: {consensus['consensus_level']}, {latency}ms")

        return DebateResult(
            topic=topic,
            domains=valid_domains,
            rounds=rounds,
            consensus_level=consensus["consensus_level"],
            summary=consensus["summary"],
            action_plan=consensus["action_plan"],
            dissent=consensus["dissent"],
            total_rounds=len(set(r.round_num for r in rounds)),
            latency_ms=latency,
            method="debate_graph",
        )

    # ─── Round 1: 독립 분석 ───

    async def _round_independent(
        self, topic: str, domains: list[str], context: dict = None
    ) -> list[DebateRound]:
        """각 전문가가 독립적으로 분석 (병렬 실행)"""

        async def _analyze(domain: str) -> DebateRound:
            expert = EXPERT_DEFINITIONS[domain]
            system = (
                f"당신은 {expert['role']}입니다. {expert['goal']}\n"
                f"배경: {expert['backstory']}\n\n"
                f"주어진 상황을 분석하고 구체적인 의견을 제시하세요. "
                f"3~5문장으로 간결하게, 한국어로 작성하세요."
            )

            prompt_parts = [f"## 토론 주제\n{topic}"]
            if context:
                prompt_parts.append("\n## 상황 정보")
                for k, v in context.items():
                    prompt_parts.append(f"- {k}: {v}")

            try:
                response = await self.llm_pool.chat(
                    prompt="\n".join(prompt_parts),
                    system=system,
                    max_tokens=400,
                )
                return DebateRound(
                    round_num=1,
                    expert_domain=domain,
                    expert_role=expert["role"],
                    content=response.text,
                )
            except Exception as e:
                log.error(f"Round 1 분석 실패 [{domain}]: {e}")
                return DebateRound(
                    round_num=1,
                    expert_domain=domain,
                    expert_role=expert["role"],
                    content=f"(분석 실패: {e})",
                )

        tasks = [_analyze(d) for d in domains]
        return list(await asyncio.gather(*tasks))

    # ─── Round 2: 상호 비판 ───

    async def _round_critique(
        self,
        topic: str,
        domains: list[str],
        round1: list[DebateRound],
        context: dict = None,
    ) -> list[DebateRound]:
        """각 전문가가 다른 전문가들의 의견을 읽고 비판/보완 (병렬 실행)"""

        # Round 1 결과를 텍스트로 정리
        others_text_map = {}
        for domain in domains:
            others = [r for r in round1 if r.expert_domain != domain]
            others_text = "\n\n".join(
                f"### {r.expert_role} ({r.expert_domain})\n{r.content}"
                for r in others
            )
            others_text_map[domain] = others_text

        async def _critique(domain: str) -> DebateRound:
            expert = EXPERT_DEFINITIONS[domain]
            system = CRITIC_TEMPLATE.format(
                role=expert["role"],
                goal=expert["goal"],
            )

            prompt = (
                f"## 토론 주제\n{topic}\n\n"
                f"## 다른 전문가들의 분석\n{others_text_map[domain]}\n\n"
                f"위 분석들을 읽고, 당신의 전문 관점에서 비판/보완하세요."
            )

            try:
                response = await self.llm_pool.chat(
                    prompt=prompt,
                    system=system,
                    max_tokens=400,
                )
                return DebateRound(
                    round_num=2,
                    expert_domain=domain,
                    expert_role=expert["role"],
                    content=response.text,
                )
            except Exception as e:
                log.error(f"Round 2 비판 실패 [{domain}]: {e}")
                return DebateRound(
                    round_num=2,
                    expert_domain=domain,
                    expert_role=expert["role"],
                    content=f"(비판 실패: {e})",
                )

        tasks = [_critique(d) for d in domains]
        return list(await asyncio.gather(*tasks))

    # ─── Round 3: 중재자 합의 ───

    async def _round_consensus(
        self,
        topic: str,
        all_rounds: list[DebateRound],
        domains: list[str],
    ) -> dict:
        """중재자가 모든 라운드 결과를 종합하여 합의 도출"""

        # 모든 라운드를 텍스트로 정리
        round1 = [r for r in all_rounds if r.round_num == 1]
        round2 = [r for r in all_rounds if r.round_num == 2]

        prompt_parts = [f"## 토론 주제\n{topic}\n"]

        prompt_parts.append("## Round 1: 독립 분석")
        for r in round1:
            prompt_parts.append(f"### {r.expert_role}\n{r.content}\n")

        if round2:
            prompt_parts.append("## Round 2: 상호 비판")
            for r in round2:
                prompt_parts.append(f"### {r.expert_role} (비판)\n{r.content}\n")

        prompt_parts.append(
            "\n위 전문가들의 분석과 비판을 종합하여 합의된 결론을 도출하세요."
        )

        try:
            response = await self.llm_pool.chat(
                prompt="\n".join(prompt_parts),
                system=MODERATOR_SYSTEM,
                max_tokens=600,
            )
            return self._parse_consensus(response.text)
        except Exception as e:
            log.error(f"Round 3 합의 실패: {e}")
            return {
                "consensus_level": "no_consensus",
                "summary": f"합의 도출 실패: {e}",
                "action_plan": [],
                "dissent": "",
            }

    def _parse_consensus(self, text: str) -> dict:
        """중재자 응답 파싱"""
        result = {
            "consensus_level": "partial_consensus",
            "summary": text.strip(),
            "action_plan": [],
            "dissent": "없음",
        }

        current_field = None
        action_lines = []

        for line in text.split("\n"):
            stripped = line.strip()
            lower = stripped.lower()

            if lower.startswith("consensus_level:"):
                val = stripped.split(":", 1)[1].strip()
                if val in ("strong_consensus", "partial_consensus", "no_consensus"):
                    result["consensus_level"] = val
                current_field = None
            elif lower.startswith("summary:"):
                result["summary"] = stripped.split(":", 1)[1].strip()
                current_field = "summary"
            elif lower.startswith("action_plan:"):
                current_field = "action_plan"
            elif lower.startswith("dissent:"):
                result["dissent"] = stripped.split(":", 1)[1].strip()
                current_field = "dissent"
            elif current_field == "action_plan" and stripped.startswith("- "):
                action_lines.append(stripped[2:].strip())
            elif current_field == "summary" and stripped and not stripped.startswith("- "):
                result["summary"] += " " + stripped

        if action_lines:
            result["action_plan"] = action_lines

        return result
