#!/usr/bin/env python3
"""
LLM Pool — 분산 LLM 서버 통합 관리 + 자동 페일오버.

Tailscale 네트워크 상의 모든 LLM 서버를 하나의 풀로 관리.
헬스체크 → 살아있는 서버 중 최적 선택 → 실패 시 자동 폴백.

사용:
    pool = LLMPool()
    await pool.start()
    response = await pool.chat("안녕", system="당신은 도우미입니다")
    response = await pool.chat_with_vision("이 사진 설명해줘", image_b64="...")
"""

import os
import time
import asyncio
import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional

import httpx

log = logging.getLogger("llm_pool")


class ServerCapability(Enum):
    TEXT = "text"
    VISION = "vision"
    EMBEDDING = "embedding"
    STT = "stt"
    CLASSIFICATION = "classification"


class ServerTier(Enum):
    """서버 성능 등급 — 복잡한 작업은 높은 티어로 라우팅"""
    HIGH = 3      # MiniCPM-V 8B, Gemma-3 12B
    MEDIUM = 2    # Gemma-3 4B, BitNet 2.7B
    LOW = 1       # BitNet, 경량 분류


@dataclass
class LLMServer:
    """개별 LLM 서버 엔드포인트"""
    name: str
    base_url: str
    model_name: str
    tier: ServerTier
    capabilities: set[ServerCapability] = field(default_factory=lambda: {ServerCapability.TEXT})
    max_tokens_default: int = 1024

    # Runtime state
    is_healthy: bool = False
    last_check: float = 0.0
    last_latency_ms: int = 0
    consecutive_failures: int = 0
    total_requests: int = 0
    total_errors: int = 0

    @property
    def error_rate(self) -> float:
        if self.total_requests == 0:
            return 0.0
        return self.total_errors / self.total_requests


@dataclass
class PoolResponse:
    """풀 응답"""
    text: str
    server_name: str
    model: str
    latency_ms: int
    fallback_used: bool = False


class LLMPool:
    """분산 LLM 서버 풀 — 헬스체크 + 자동 페일오버 + 부하 분산"""

    def __init__(self):
        self.servers: list[LLMServer] = []
        self.http: Optional[httpx.AsyncClient] = None
        self._health_task: Optional[asyncio.Task] = None
        self._health_interval = 30  # 초

        # 서버 등록 (환경변수 or 기본값)
        self._register_default_servers()

    def _register_default_servers(self):
        """Tailscale 네트워크 상의 모든 LLM 서버 등록"""

        # Main PC — MiniCPM-V (비전 + 텍스트)
        self.servers.append(LLMServer(
            name="main_pc_vision",
            base_url=os.getenv("VISION_URL", "http://100.101.127.124:8080"),
            model_name="minicpm-v-4.5",
            tier=ServerTier.HIGH,
            capabilities={ServerCapability.TEXT, ServerCapability.VISION},
            max_tokens_default=1024,
        ))

        # Speech PC — Gemma-3 4B (텍스트)
        self.servers.append(LLMServer(
            name="speech_pc_llm",
            base_url=os.getenv("SPEECH_LLM_URL", "http://100.121.84.80:8179"),
            model_name="gemma-3-4b",
            tier=ServerTier.MEDIUM,
            capabilities={ServerCapability.TEXT},
        ))

        # Steam Deck — Gemma-3 4B / BitNet (유동적)
        self.servers.append(LLMServer(
            name="steamdeck",
            base_url=os.getenv("STEAMDECK_URL", "http://100.98.177.14:8080"),
            model_name="gemma-3-4b",
            tier=ServerTier.MEDIUM,
            capabilities={ServerCapability.TEXT, ServerCapability.CLASSIFICATION},
        ))

        # Steam Deck — 분류 전용 포트
        self.servers.append(LLMServer(
            name="steamdeck_classify",
            base_url=os.getenv("STEAMDECK_CLASSIFY_URL", "http://100.98.177.14:8082"),
            model_name="bitnet-2.7b",
            tier=ServerTier.LOW,
            capabilities={ServerCapability.TEXT, ServerCapability.CLASSIFICATION},
        ))

        # 새 BitNet OpenVINO (빌드 중 — 환경변수로 추가)
        bitnet_url = os.getenv("BITNET_OPENVINO_URL")
        if bitnet_url:
            self.servers.append(LLMServer(
                name="bitnet_openvino",
                base_url=bitnet_url,
                model_name="bitnet-openvino",
                tier=ServerTier.LOW,
                capabilities={ServerCapability.TEXT, ServerCapability.CLASSIFICATION},
            ))

        # 추가 서버는 환경변수로 동적 등록 가능
        # EXTRA_LLM_1_URL, EXTRA_LLM_1_NAME, EXTRA_LLM_1_TIER ...
        for i in range(1, 6):
            url = os.getenv(f"EXTRA_LLM_{i}_URL")
            if url:
                self.servers.append(LLMServer(
                    name=os.getenv(f"EXTRA_LLM_{i}_NAME", f"extra_{i}"),
                    base_url=url,
                    model_name=os.getenv(f"EXTRA_LLM_{i}_MODEL", "unknown"),
                    tier=ServerTier(int(os.getenv(f"EXTRA_LLM_{i}_TIER", "2"))),
                    capabilities={ServerCapability.TEXT},
                ))

    async def start(self):
        """풀 시작 — HTTP 클라이언트 + 헬스체크 루프"""
        self.http = httpx.AsyncClient(timeout=httpx.Timeout(90.0, connect=5.0))
        # 초기 헬스체크
        await self._check_all_health()
        # 주기적 헬스체크 시작
        self._health_task = asyncio.create_task(self._health_loop())
        healthy = [s.name for s in self.servers if s.is_healthy]
        log.info(f"LLM Pool started: {len(healthy)}/{len(self.servers)} healthy — {healthy}")

    async def stop(self):
        """풀 정지"""
        if self._health_task:
            self._health_task.cancel()
        if self.http:
            await self.http.aclose()

    # ─── 공개 API ───

    async def chat(
        self,
        prompt: str,
        system: str = "",
        messages: list[dict] = None,
        max_tokens: int = 1024,
        prefer_tier: ServerTier = None,
        require_capability: ServerCapability = ServerCapability.TEXT,
    ) -> PoolResponse:
        """텍스트 채팅 — 자동 서버 선택 + 페일오버"""
        if messages is None:
            messages = []
            if system:
                messages.append({"role": "system", "content": system})
            messages.append({"role": "user", "content": prompt})

        candidates = self._select_servers(require_capability, prefer_tier)
        if not candidates:
            raise RuntimeError("사용 가능한 LLM 서버 없음")

        last_error = None
        fallback_used = False

        for i, server in enumerate(candidates):
            if i > 0:
                fallback_used = True
            try:
                result = await self._call_chat(server, messages, max_tokens)
                return PoolResponse(
                    text=result,
                    server_name=server.name,
                    model=server.model_name,
                    latency_ms=server.last_latency_ms,
                    fallback_used=fallback_used,
                )
            except Exception as e:
                last_error = e
                server.consecutive_failures += 1
                server.total_errors += 1
                log.warning(f"LLM 호출 실패 [{server.name}]: {e}, 다음 서버 시도")

        raise RuntimeError(f"모든 LLM 서버 실패 ({len(candidates)}개 시도): {last_error}")

    async def chat_with_vision(
        self,
        prompt: str,
        image_base64: str,
        system: str = "",
        max_tokens: int = 512,
    ) -> PoolResponse:
        """비전 채팅 — VISION 지원 서버만 사용"""
        messages = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}}
            ]
        })

        candidates = self._select_servers(ServerCapability.VISION)
        if not candidates:
            raise RuntimeError("비전 지원 LLM 서버 없음")

        last_error = None
        for server in candidates:
            try:
                result = await self._call_chat(server, messages, max_tokens)
                return PoolResponse(
                    text=result,
                    server_name=server.name,
                    model=server.model_name,
                    latency_ms=server.last_latency_ms,
                )
            except Exception as e:
                last_error = e
                log.warning(f"Vision 호출 실패 [{server.name}]: {e}")

        raise RuntimeError(f"비전 LLM 서버 모두 실패: {last_error}")

    def get_status(self) -> dict:
        """전체 풀 상태"""
        return {
            "total_servers": len(self.servers),
            "healthy": sum(1 for s in self.servers if s.is_healthy),
            "servers": [
                {
                    "name": s.name,
                    "url": s.base_url,
                    "model": s.model_name,
                    "tier": s.tier.name,
                    "healthy": s.is_healthy,
                    "latency_ms": s.last_latency_ms,
                    "capabilities": [c.value for c in s.capabilities],
                    "error_rate": round(s.error_rate, 3),
                    "total_requests": s.total_requests,
                }
                for s in self.servers
            ]
        }

    # ─── 내부 ───

    def _select_servers(
        self,
        require: ServerCapability = ServerCapability.TEXT,
        prefer_tier: ServerTier = None,
    ) -> list[LLMServer]:
        """사용 가능한 서버 선택 (건강한 서버 우선, 티어 높은 순)"""
        eligible = [
            s for s in self.servers
            if require in s.capabilities
        ]

        # 건강한 서버 먼저, 그 안에서 티어 높은 순
        healthy = [s for s in eligible if s.is_healthy]
        unhealthy = [s for s in eligible if not s.is_healthy]

        if prefer_tier:
            # 선호 티어를 먼저 배치
            healthy.sort(key=lambda s: (
                0 if s.tier == prefer_tier else 1,
                -s.tier.value,
                s.error_rate,
            ))
        else:
            healthy.sort(key=lambda s: (-s.tier.value, s.error_rate))

        # 건강한 서버 → 비건강 서버 (최후 수단)
        return healthy + unhealthy

    async def _call_chat(
        self,
        server: LLMServer,
        messages: list[dict],
        max_tokens: int,
    ) -> str:
        """단일 서버에 채팅 요청"""
        t0 = time.time()
        server.total_requests += 1

        payload = {
            "model": server.model_name,
            "messages": messages,
            "max_tokens": max_tokens,
        }

        r = await self.http.post(
            f"{server.base_url}/v1/chat/completions",
            json=payload,
            timeout=90.0,
        )
        r.raise_for_status()
        data = r.json()

        server.last_latency_ms = int((time.time() - t0) * 1000)
        server.consecutive_failures = 0
        return data["choices"][0]["message"]["content"]

    async def _check_all_health(self):
        """모든 서버 헬스체크 (병렬)"""
        tasks = [self._check_health(s) for s in self.servers]
        await asyncio.gather(*tasks, return_exceptions=True)

    async def _check_health(self, server: LLMServer):
        """단일 서버 헬스체크 — 1토큰 생성 테스트"""
        try:
            t0 = time.time()
            r = await self.http.post(
                f"{server.base_url}/v1/chat/completions",
                json={
                    "model": server.model_name,
                    "messages": [{"role": "user", "content": "hi"}],
                    "max_tokens": 1,
                },
                timeout=10.0,
            )
            r.raise_for_status()
            server.is_healthy = True
            server.last_latency_ms = int((time.time() - t0) * 1000)
            server.consecutive_failures = 0
            server.last_check = time.time()
        except Exception as e:
            server.is_healthy = False
            server.consecutive_failures += 1
            server.last_check = time.time()
            log.debug(f"Health check failed [{server.name}]: {e}")

    async def _health_loop(self):
        """주기적 헬스체크 루프"""
        while True:
            await asyncio.sleep(self._health_interval)
            try:
                await self._check_all_health()
                healthy = [s.name for s in self.servers if s.is_healthy]
                log.debug(f"Health check: {len(healthy)}/{len(self.servers)} healthy")
            except asyncio.CancelledError:
                break
            except Exception as e:
                log.error(f"Health check loop error: {e}")


# ─── LangChain 통합 ───

def create_langchain_llm(pool: LLMPool, prefer_tier: ServerTier = None):
    """LangChain ChatOpenAI 호환 LLM 생성 — 풀의 가장 건강한 서버 사용

    LangChain이 설치되어 있으면 ChatOpenAI를, 아니면 None 반환.
    CrewAI 등에서 이 LLM을 사용.
    """
    try:
        from langchain_openai import ChatOpenAI
    except ImportError:
        log.warning("langchain-openai 미설치 — LangChain LLM 생성 불가")
        return None

    # 가장 건강하고 티어 높은 서버 선택
    candidates = pool._select_servers(ServerCapability.TEXT, prefer_tier)
    if not candidates:
        log.warning("사용 가능한 서버 없음 — LangChain LLM 생성 불가")
        return None

    server = candidates[0]
    return ChatOpenAI(
        model=server.model_name,
        base_url=f"{server.base_url}/v1",
        api_key="not-needed",  # 로컬 서버는 API 키 불필요
        max_tokens=1024,
        temperature=0.7,
    )


def create_vision_llm(pool: LLMPool):
    """비전 지원 LangChain LLM 생성"""
    try:
        from langchain_openai import ChatOpenAI
    except ImportError:
        return None

    candidates = pool._select_servers(ServerCapability.VISION)
    if not candidates:
        return None

    server = candidates[0]
    return ChatOpenAI(
        model=server.model_name,
        base_url=f"{server.base_url}/v1",
        api_key="not-needed",
        max_tokens=512,
    )
