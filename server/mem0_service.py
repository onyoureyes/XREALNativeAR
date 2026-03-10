#!/usr/bin/env python3
"""
XREAL Mem0 Service — 사실 추출 + A.U.D.N. 사이클

Mem0를 로컬 LLM + 로컬 임베딩으로 구동.
Orchestrator에서 호출하거나 독립 실행 가능.

구성:
- LLM: llama.cpp 서버 (MiniCPM-V or Gemma) — OpenAI-compatible API
- Embedder: HuggingFace sentence-transformers (로컬, 무료)
- Vector Store: ChromaDB (임베디드 모드, 서버 불필요)

Usage:
    pip install mem0ai chromadb sentence-transformers
    python mem0_service.py
"""

import os
import json
import time
import logging
from typing import Optional
from datetime import datetime

from mem0 import Memory

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
log = logging.getLogger("mem0_service")

# ─── Config ───

LLM_BASE_URL = os.getenv("MEM0_LLM_URL", "http://127.0.0.1:8080/v1")
LLM_MODEL = os.getenv("MEM0_LLM_MODEL", "minicpm-v")
CHROMA_PATH = os.getenv("MEM0_CHROMA_PATH", "./data/chroma_mem0")
EMBED_MODEL = os.getenv("MEM0_EMBED_MODEL", "sentence-transformers/all-MiniLM-L6-v2")

# all-MiniLM-L6-v2: 384-dim, 22MB, 매우 빠름
# multilingual: paraphrase-multilingual-MiniLM-L12-v2 (384-dim, 한국어 지원)
EMBED_DIMS = int(os.getenv("MEM0_EMBED_DIMS", "384"))


def create_mem0_config() -> dict:
    """Mem0 설정 생성 — 로컬 LLM + 로컬 임베딩 + ChromaDB"""
    return {
        "llm": {
            "provider": "openai",
            "config": {
                "model": LLM_MODEL,
                "openai_base_url": LLM_BASE_URL,
                "api_key": "not-needed",  # llama.cpp는 키 불필요
                "temperature": 0.1,
                "max_tokens": 1024,
            },
        },
        "embedder": {
            "provider": "huggingface",
            "config": {
                "model": EMBED_MODEL,
                "embedding_dims": EMBED_DIMS,
            },
        },
        "vector_store": {
            "provider": "chroma",
            "config": {
                "collection_name": "xreal_memories",
                "path": CHROMA_PATH,
                "embedding_model_dims": EMBED_DIMS,
            },
        },
        "version": "v1.1",
    }


class Mem0Service:
    """Mem0 래퍼 — 사실 추출 + 검색 + A.U.D.N."""

    def __init__(self, config: dict = None):
        self.config = config or create_mem0_config()
        log.info(f"Mem0 초기화: LLM={self.config['llm']['config']['model']}, "
                 f"Embed={self.config['embedder']['config']['model']}")
        self.memory = Memory.from_config(self.config)
        log.info("Mem0 초기화 완료")

    def add(self, content: str, user_id: str = "teacher",
            metadata: Optional[dict] = None) -> dict:
        """
        대화/관찰을 추가 → Mem0가 자동으로 사실 추출 + A.U.D.N. 수행.

        A.U.D.N. 사이클:
        - Add: 새로운 사실 → 추가
        - Update: 기존 사실과 충돌 → 업데이트
        - Delete: 무효화된 사실 → 삭제
        - Noop: 이미 알고 있는 사실 → 무시
        """
        messages = [{"role": "user", "content": content}]

        try:
            result = self.memory.add(
                messages=messages,
                user_id=user_id,
                metadata=metadata or {"source": "xreal", "timestamp": time.time()},
            )
            log.info(f"Mem0 add: user={user_id}, facts_extracted={len(result.get('results', []))}")
            return result
        except Exception as e:
            log.error(f"Mem0 add 실패: {e}")
            return {"error": str(e), "results": []}

    def search(self, query: str, user_id: str = "teacher",
               limit: int = 5) -> list[dict]:
        """시맨틱 검색 — 관련 기억 조회"""
        try:
            results = self.memory.search(
                query=query,
                user_id=user_id,
                limit=limit,
            )
            log.info(f"Mem0 search: query='{query[:50]}', results={len(results.get('results', []))}")
            return results.get("results", [])
        except Exception as e:
            log.error(f"Mem0 search 실패: {e}")
            return []

    def get_all(self, user_id: str = "teacher") -> list[dict]:
        """특정 사용자의 모든 기억 조회"""
        try:
            results = self.memory.get_all(user_id=user_id)
            return results.get("results", [])
        except Exception as e:
            log.error(f"Mem0 get_all 실패: {e}")
            return []

    def delete(self, memory_id: str) -> bool:
        """특정 기억 삭제"""
        try:
            self.memory.delete(memory_id=memory_id)
            log.info(f"Mem0 delete: {memory_id}")
            return True
        except Exception as e:
            log.error(f"Mem0 delete 실패: {e}")
            return False

    def add_episode(self, event_type: str, content: str,
                    user_id: str = "teacher",
                    extra_context: str = "") -> dict:
        """
        에피소드 이벤트를 Mem0에 추가.
        event_type에 따라 다른 프롬프트 래핑.
        """
        context_prefix = {
            "vision": "[시각 관찰]",
            "speech": "[대화]",
            "sensor": "[센서 데이터]",
            "interaction": "[사용자 상호작용]",
            "reflection": "[일일 반성]",
        }
        prefix = context_prefix.get(event_type, f"[{event_type}]")
        full_content = f"{prefix} {content}"
        if extra_context:
            full_content += f"\n맥락: {extra_context}"

        return self.add(
            content=full_content,
            user_id=user_id,
            metadata={
                "source": "xreal",
                "event_type": event_type,
                "timestamp": time.time(),
            },
        )

    def get_context_for_storyteller(self, situation: str = "",
                                    user_id: str = "teacher",
                                    limit: int = 10) -> str:
        """
        이야기꾼에게 전달할 컨텍스트 구성.
        현재 상황과 관련된 기억을 검색해서 요약.
        """
        if situation:
            memories = self.search(situation, user_id=user_id, limit=limit)
        else:
            memories = self.get_all(user_id=user_id)[-limit:]

        if not memories:
            return "관련 기억 없음"

        lines = []
        for mem in memories:
            text = mem.get("memory", "")
            ts = mem.get("metadata", {}).get("timestamp", "")
            if ts:
                dt = datetime.fromtimestamp(float(ts)).strftime("%m/%d %H:%M")
                lines.append(f"[{dt}] {text}")
            else:
                lines.append(text)

        return "\n".join(lines)


# ─── Standalone test ───

if __name__ == "__main__":
    print("=== Mem0 Service 테스트 ===")
    print(f"LLM: {LLM_BASE_URL} ({LLM_MODEL})")
    print(f"Embedder: {EMBED_MODEL} ({EMBED_DIMS}d)")
    print(f"ChromaDB: {CHROMA_PATH}")
    print()

    try:
        svc = Mem0Service()
        print("[OK] Mem0 초기화 성공\n")

        # 테스트 1: 사실 추가
        print("--- 테스트 1: 사실 추가 ---")
        result = svc.add("오늘 학교에서 민수가 수학 시간에 집중을 잘 했다. 최근 2주간 개선 추세.")
        print(f"결과: {json.dumps(result, ensure_ascii=False, indent=2)}\n")

        # 테스트 2: 검색
        print("--- 테스트 2: 검색 ---")
        results = svc.search("민수 수학")
        for r in results:
            print(f"  - {r.get('memory', '')}")
        print()

        # 테스트 3: 전체 조회
        print("--- 테스트 3: 전체 조회 ---")
        all_mems = svc.get_all()
        print(f"총 {len(all_mems)}개 기억")
        for m in all_mems[:5]:
            print(f"  - {m.get('memory', '')}")

        print("\n[OK] 모든 테스트 완료")

    except Exception as e:
        print(f"\n[ERROR] {e}")
        print("\n필요 조건:")
        print("  1. pip install mem0ai chromadb sentence-transformers")
        print(f"  2. LLM 서버 실행 중: {LLM_BASE_URL}")
        print("  3. 첫 실행 시 임베딩 모델 다운로드 (~22MB)")
