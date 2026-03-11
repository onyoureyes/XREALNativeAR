#!/usr/bin/env python3
"""
Expert Crews — CrewAI 기반 전문가 팀.

Android ExpertTeamManager + MissionConductor를 서버측 CrewAI로 대체.
각 전문가는 역할/목표/백스토리를 가지며, 도구를 사용해 자율적으로 작업.

사용:
    crew_manager = ExpertCrewManager(llm_pool)
    result = await crew_manager.delegate("behavior", query="학생A 문제행동 분석", context={...})
"""

import asyncio
import logging
from typing import Optional
from concurrent.futures import ThreadPoolExecutor

log = logging.getLogger("expert_crews")

# CrewAI는 동기 라이브러리 → ThreadPoolExecutor로 async 래핑
_executor = ThreadPoolExecutor(max_workers=3, thread_name_prefix="crewai")


# ═══════════════════════════════════════════
# 전문가 정의
# ═══════════════════════════════════════════

EXPERT_DEFINITIONS = {
    "behavior": {
        "role": "문제행동 분석 전문가",
        "goal": "학생의 문제행동 원인을 기능적으로 분석하고, 증거 기반 중재 방안을 제시합니다.",
        "backstory": (
            "20년 경력의 응용행동분석(ABA) 전문가입니다. "
            "학생의 행동을 선행사건-행동-결과(ABC) 프레임으로 분석하고, "
            "긍정적 행동지원(PBS) 전략을 설계합니다. "
            "과거 중재 이력과 효과를 참고하여 맞춤형 방안을 제안합니다."
        ),
    },
    "lesson": {
        "role": "수업 설계 전문가",
        "goal": "특수교육 학생의 수준과 필요에 맞는 수업 활동을 설계합니다.",
        "backstory": (
            "15년 경력의 특수교육 교육과정 전문가입니다. "
            "개별화교육프로그램(IEP) 기반으로 학생별 맞춤 활동을 설계합니다. "
            "다감각 접근법, 시각적 지원, 구조화된 교수를 활용합니다."
        ),
    },
    "health": {
        "role": "건강 관리 전문가",
        "goal": "교사의 신체/정신 건강 상태를 모니터링하고, 실행 가능한 건강 관리 조언을 제공합니다.",
        "backstory": (
            "산업보건 전문의이자 스포츠의학 전문가입니다. "
            "워치 센서 데이터(심박, HRV, SpO2, 피부온도)를 해석하고, "
            "과로/스트레스 징후를 조기에 감지합니다. "
            "러닝 코칭과 회복 전략도 제공합니다."
        ),
    },
    "social": {
        "role": "사회적 관계 전문가",
        "goal": "교사와 학생/동료/학부모 간의 관계 맥락을 분석하고, 소통 전략을 제안합니다.",
        "backstory": (
            "학교 상담사이자 조직심리 전문가입니다. "
            "인물 이력과 관계 패턴을 분석하여 효과적인 소통 전략을 제안합니다. "
            "갈등 상황에서 중재 접근법을 안내합니다."
        ),
    },
    "planning": {
        "role": "일정 및 전략 전문가",
        "goal": "교사의 시간 관리, 일정 최적화, 장기 프로젝트 전략을 수립합니다.",
        "backstory": (
            "교육행정 전문가이자 프로젝트 매니저입니다. "
            "IEP 일정, 학교 행사, 개인 일정을 통합 관리하며, "
            "우선순위 조정과 시간 블록 최적화를 제안합니다."
        ),
    },
    "crisis": {
        "role": "위기 대응 전문가",
        "goal": "긴급 상황(자해, 타해, 도주 등)에 즉각적이고 안전한 대응 방안을 제시합니다.",
        "backstory": (
            "위기개입 전문가이자 안전관리 담당자입니다. "
            "학생의 안전을 최우선으로 하며, 비폭력적 위기개입(NCI) 프로토콜에 따라 "
            "단계적 대응을 안내합니다. 사후 보고서 작성도 지원합니다."
        ),
    },
}


# ═══════════════════════════════════════════
# CrewAI 팀 매니저
# ═══════════════════════════════════════════

class ExpertCrewManager:
    """CrewAI 기반 전문가 팀 관리자"""

    def __init__(self, llm_pool):
        """
        Args:
            llm_pool: LLMPool 인스턴스 (LangChain LLM 생성용)
        """
        self.llm_pool = llm_pool
        self._crewai_available = False

        try:
            import crewai
            self._crewai_available = True
            log.info(f"CrewAI {crewai.__version__} 사용 가능")
        except ImportError:
            log.warning("CrewAI 미설치 — 폴백 모드 (직접 LLM 호출)")

    async def delegate(
        self,
        domain: str,
        query: str,
        context: dict = None,
        max_iter: int = 3,
    ) -> dict:
        """전문가에게 작업 위임

        Args:
            domain: 전문가 도메인 (behavior/lesson/health/social/planning/crisis)
            query: 요청 내용
            context: 추가 컨텍스트 (에피소드, 인물 정보 등)
            max_iter: CrewAI 최대 반복 횟수 (토큰 절약)

        Returns:
            {expert_name, domain, insight, action_taken, latency_ms}
        """
        if domain not in EXPERT_DEFINITIONS:
            return {"error": f"알 수 없는 전문가 도메인: {domain}"}

        expert_def = EXPERT_DEFINITIONS[domain]

        if self._crewai_available:
            return await self._delegate_crewai(domain, expert_def, query, context, max_iter)
        else:
            return await self._delegate_fallback(domain, expert_def, query, context)

    async def _delegate_crewai(
        self, domain, expert_def, query, context, max_iter
    ) -> dict:
        """CrewAI를 사용한 전문가 위임"""
        import time as _time
        t0 = _time.time()

        def _run_crew():
            from crewai import Agent, Task, Crew, Process
            from llm_pool import create_langchain_llm
            from langchain_tools import get_expert_tools

            llm = create_langchain_llm(self.llm_pool)
            if llm is None:
                raise RuntimeError("LLM 서버 없음")

            agent = Agent(
                role=expert_def["role"],
                goal=expert_def["goal"],
                backstory=expert_def["backstory"],
                tools=get_expert_tools(),
                llm=llm,
                verbose=False,
                max_iter=max_iter,
                allow_delegation=False,
            )

            context_str = ""
            if context:
                context_str = "\n추가 컨텍스트:\n"
                for k, v in context.items():
                    context_str += f"- {k}: {v}\n"

            task = Task(
                description=f"{query}{context_str}",
                expected_output="한국어로 분석 결과와 구체적인 행동 제안을 작성하세요. 300자 이내.",
                agent=agent,
            )

            crew = Crew(
                agents=[agent],
                tasks=[task],
                process=Process.sequential,
                verbose=False,
            )

            result = crew.kickoff()
            return str(result)

        try:
            # CrewAI는 동기 → ThreadPool에서 실행
            loop = asyncio.get_event_loop()
            result_text = await loop.run_in_executor(_executor, _run_crew)
            latency = int((_time.time() - t0) * 1000)

            return {
                "expert_name": expert_def["role"],
                "domain": domain,
                "insight": result_text,
                "action_taken": None,
                "latency_ms": latency,
                "method": "crewai",
            }
        except Exception as e:
            log.error(f"CrewAI 실행 실패 [{domain}]: {e}, 폴백 사용")
            return await self._delegate_fallback(domain, expert_def, query, context)

    async def _delegate_fallback(
        self, domain, expert_def, query, context
    ) -> dict:
        """CrewAI 없을 때 직접 LLM 호출로 폴백"""
        import time as _time
        t0 = _time.time()

        system_prompt = (
            f"당신은 {expert_def['role']}입니다.\n"
            f"목표: {expert_def['goal']}\n"
            f"배경: {expert_def['backstory']}\n\n"
            f"한국어로 분석 결과와 구체적인 행동 제안을 작성하세요. 300자 이내."
        )

        user_prompt = query
        if context:
            user_prompt += "\n\n추가 컨텍스트:\n"
            for k, v in context.items():
                user_prompt += f"- {k}: {v}\n"

        try:
            response = await self.llm_pool.chat(
                prompt=user_prompt,
                system=system_prompt,
                max_tokens=512,
            )
            latency = int((_time.time() - t0) * 1000)

            return {
                "expert_name": expert_def["role"],
                "domain": domain,
                "insight": response.text,
                "action_taken": None,
                "latency_ms": latency,
                "method": "fallback_llm",
                "server_used": response.server_name,
            }
        except Exception as e:
            return {
                "expert_name": expert_def["role"],
                "domain": domain,
                "insight": f"전문가 분석 실패: {e}",
                "error": str(e),
                "latency_ms": int((_time.time() - t0) * 1000),
                "method": "error",
            }

    async def delegate_team(
        self,
        domains: list[str],
        query: str,
        context: dict = None,
    ) -> list[dict]:
        """여러 전문가에게 동시 위임 (병렬 실행)

        Args:
            domains: 전문가 도메인 목록
            query: 공통 요청
            context: 공통 컨텍스트

        Returns:
            각 전문가의 결과 리스트
        """
        tasks = [
            self.delegate(domain, query, context)
            for domain in domains
            if domain in EXPERT_DEFINITIONS
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)

        return [
            r if isinstance(r, dict) else {"error": str(r)}
            for r in results
        ]

    def list_experts(self) -> list[dict]:
        """사용 가능한 전문가 목록"""
        return [
            {
                "domain": domain,
                "role": defn["role"],
                "goal": defn["goal"],
            }
            for domain, defn in EXPERT_DEFINITIONS.items()
        ]
