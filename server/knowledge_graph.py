#!/usr/bin/env python3
"""
XREAL Knowledge Graph — Graphiti 기반 L4 시간축 지식 그래프

에피소드/대화에서 엔티티(사람, 장소, 물체)와 관계를 추출하여
시간축 지식 그래프를 구축. 시맨틱 + BM25 + 그래프 순회 하이브리드 검색.

구성:
  - Graph DB: Kuzu (임베디드, 서버 불필요)
  - LLM: llama.cpp (OpenAI-compatible, 엔티티/관계 추출용)
  - Embedder: HuggingFace sentence-transformers (로컬)

Usage:
    pip install graphiti-core[kuzu]
    python knowledge_graph.py              # 즉시 빌드
    python knowledge_graph.py --schedule   # 매월 1일 새벽 5시
"""

import os
import json
import asyncio
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
        logging.FileHandler("logs/knowledge_graph.log", encoding="utf-8"),
    ],
)
log = logging.getLogger("knowledge_graph")

# ─── Config ───

ORCHESTRATOR_URL = os.getenv("ORCHESTRATOR_URL", "http://127.0.0.1:8091")
LLM_BASE_URL = os.getenv("KG_LLM_URL", "http://127.0.0.1:8080/v1")
LLM_MODEL = os.getenv("KG_LLM_MODEL", "minicpm-v")
KUZU_DB_PATH = os.getenv("KUZU_DB_PATH", "./data/kuzu_graph")
EMBED_MODEL = os.getenv("KG_EMBED_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
REPORT_DIR = Path(os.getenv("KG_REPORT_DIR", "./data/kg_reports"))


def ensure_dirs():
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    Path("logs").mkdir(exist_ok=True)
    Path(KUZU_DB_PATH).mkdir(parents=True, exist_ok=True)


async def init_graphiti():
    """Graphiti 초기화 (Kuzu + 로컬 LLM)"""
    try:
        from graphiti_core import Graphiti
        from graphiti_core.driver.kuzu_driver import KuzuDriver
        from graphiti_core.llm_client.openai_generic_client import OpenAIGenericClient
        from graphiti_core.llm_client.config import LLMConfig
    except ImportError:
        log.error("graphiti-core[kuzu] 미설치. pip install graphiti-core[kuzu]")
        return None

    # Kuzu 드라이버 (임베디드, 서버 불필요)
    driver = KuzuDriver(db=KUZU_DB_PATH)

    # 로컬 LLM (llama.cpp OpenAI-compatible)
    llm_config = LLMConfig(
        api_key="not-needed",
        model=LLM_MODEL,
        base_url=LLM_BASE_URL,
    )
    llm_client = OpenAIGenericClient(config=llm_config)

    # HuggingFace 임베더 (Graphiti가 OpenAI 형식을 기대하므로 래퍼 사용)
    # 참고: Graphiti는 OpenAI embedder를 기본 사용하므로,
    # 로컬 임베딩은 llama.cpp --embedding 모드 또는 별도 서버 필요
    # 여기서는 기본 embedder를 그대로 사용하고, 추후 커스터마이즈
    try:
        graphiti = Graphiti(
            graph_driver=driver,
            llm_client=llm_client,
        )
        log.info(f"Graphiti 초기화 완료 (Kuzu: {KUZU_DB_PATH})")
        return graphiti
    except Exception as e:
        log.error(f"Graphiti 초기화 실패: {e}")
        return None


def fetch_episodes_for_graph(client: httpx.Client, days: int = 30) -> list[dict]:
    """그래프 구축용 에피소드 수집 (speech, interaction 위주)"""
    docs = []

    for day_offset in range(days):
        date = (datetime.now() - timedelta(days=day_offset)).strftime("%Y-%m-%d")
        try:
            r = client.get(f"{ORCHESTRATOR_URL}/v1/episode/history",
                          params={"date": date, "limit": 200})
            r.raise_for_status()
            episodes = r.json().get("episodes", [])
            for ep in episodes:
                content = ep.get("content", "")
                etype = ep.get("event_type", "")
                # 대화/상호작용/비전 이벤트만 (센서 데이터 제외)
                if content and len(content) > 20 and etype in ("speech", "interaction", "vision", "observation"):
                    docs.append({
                        "content": content,
                        "timestamp": ep.get("timestamp", 0),
                        "event_type": etype,
                        "metadata": ep.get("metadata", {}),
                    })
        except Exception as e:
            log.warning(f"에피소드 조회 실패 ({date}): {e}")

    log.info(f"그래프 구축용 에피소드: {len(docs)}건")
    return docs


async def build_graph(graphiti, episodes: list[dict]) -> dict:
    """에피소드를 Graphiti에 추가하여 지식 그래프 구축"""
    stats = {"added": 0, "failed": 0, "nodes": 0, "edges": 0}

    for i, ep in enumerate(episodes):
        try:
            ts = datetime.fromtimestamp(ep["timestamp"]) if ep["timestamp"] else datetime.now()
            result = await graphiti.add_episode(
                name=f"ep_{int(ep['timestamp'] * 1000)}",
                episode_body=ep["content"],
                source_description=f"{ep['event_type']} observation",
                reference_time=ts,
            )
            if result:
                stats["nodes"] += len(getattr(result, "nodes", []))
                stats["edges"] += len(getattr(result, "edges", []))
            stats["added"] += 1

            if (i + 1) % 10 == 0:
                log.info(f"  진행: {i + 1}/{len(episodes)} "
                        f"(노드={stats['nodes']}, 엣지={stats['edges']})")
        except Exception as e:
            log.warning(f"에피소드 추가 실패 [{i}]: {e}")
            stats["failed"] += 1

    log.info(f"그래프 구축 완료: 추가 {stats['added']}건, "
             f"실패 {stats['failed']}건, "
             f"노드 {stats['nodes']}개, 엣지 {stats['edges']}개")
    return stats


async def query_graph(graphiti, queries: list[str]) -> list[dict]:
    """그래프 검색 — 관계 탐색"""
    results = []
    for query in queries:
        try:
            search_result = await graphiti.search(query=query)
            edges = []
            for edge in getattr(search_result, "edges", []):
                edges.append({
                    "fact": getattr(edge, "fact", str(edge)),
                    "valid_at": str(getattr(edge, "valid_at", "")),
                })
            nodes = []
            for node in getattr(search_result, "nodes", []):
                nodes.append({
                    "name": getattr(node, "name", str(node)),
                    "labels": list(getattr(node, "labels", [])),
                })
            results.append({
                "query": query,
                "edges": edges[:10],
                "nodes": nodes[:10],
            })
        except Exception as e:
            log.warning(f"검색 실패: {query} — {e}")
            results.append({"query": query, "edges": [], "nodes": [], "error": str(e)})

    return results


async def run_knowledge_graph_build():
    """메인 지식 그래프 구축"""
    log.info("=" * 60)
    log.info(f"지식 그래프 구축 시작: {datetime.now().isoformat()}")
    log.info("=" * 60)

    ensure_dirs()

    # 1. Graphiti 초기화
    graphiti = await init_graphiti()
    if graphiti is None:
        log.error("Graphiti 초기화 실패 — 중단")
        return

    with httpx.Client(timeout=httpx.Timeout(30.0, connect=5.0)) as client:
        # 2. 에피소드 수집
        episodes = fetch_episodes_for_graph(client, days=30)
        if not episodes:
            log.warning("에피소드 없음 — 중단")
            return

        # 3. 그래프 구축
        stats = await build_graph(graphiti, episodes)

        # 4. 샘플 쿼리
        sample_queries = [
            "가장 자주 만나는 사람은 누구인가?",
            "최근 갈등이 있었던 관계는?",
            "학교에서 일어난 주요 사건은?",
        ]
        query_results = await query_graph(graphiti, sample_queries)

        # 5. 인사이트 생성
        insights = []
        for qr in query_results:
            if qr.get("edges"):
                facts = [e["fact"] for e in qr["edges"][:3]]
                insights.append(f"[{qr['query']}] {'; '.join(facts)}")

        # 6. 리포트 저장
        report = {
            "date": datetime.now().strftime("%Y-%m-%d"),
            "generated_at": datetime.now().isoformat(),
            "build_stats": stats,
            "sample_queries": query_results,
            "insights": insights,
        }
        report_path = REPORT_DIR / f"kg_report_{report['date']}.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        log.info(f"리포트 저장: {report_path}")

        # 7. Mem0에 인사이트 저장
        for insight in insights:
            try:
                client.post(
                    f"{ORCHESTRATOR_URL}/v1/mem0/add",
                    json={
                        "content": f"[지식 그래프 분석] {insight}",
                        "user_id": "teacher",
                        "event_type": "reflection",
                    },
                )
            except Exception:
                pass

    # 8. 정리
    try:
        await graphiti.close()
    except Exception:
        pass

    log.info("=" * 60)
    log.info(f"지식 그래프 구축 완료: {stats}")
    log.info("=" * 60)


def schedule_monthly():
    """매월 1일 새벽 5시 실행"""
    import sched
    import time as time_module

    scheduler = sched.scheduler(time_module.time, time_module.sleep)

    def next_first_5am() -> float:
        now = datetime.now()
        if now.day == 1 and now.hour < 5:
            target = now.replace(hour=5, minute=0, second=0, microsecond=0)
        else:
            if now.month == 12:
                target = now.replace(year=now.year + 1, month=1, day=1,
                                    hour=5, minute=0, second=0, microsecond=0)
            else:
                target = now.replace(month=now.month + 1, day=1,
                                    hour=5, minute=0, second=0, microsecond=0)
        return (target - now).total_seconds()

    def scheduled_run():
        try:
            asyncio.run(run_knowledge_graph_build())
        except Exception as e:
            log.error(f"지식 그래프 에러: {e}", exc_info=True)
        delay = next_first_5am()
        scheduler.enter(delay, 1, scheduled_run)
        log.info(f"다음 실행: {delay / 86400:.1f}일 후")

    delay = next_first_5am()
    log.info(f"지식 그래프 월간 스케줄러 시작. 첫 실행: {delay / 86400:.1f}일 후")
    scheduler.enter(delay, 1, scheduled_run)
    scheduler.run()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="XREAL Knowledge Graph (Graphiti)")
    parser.add_argument("--schedule", action="store_true", help="매월 1일 새벽 5시 스케줄")
    args = parser.parse_args()

    if args.schedule:
        schedule_monthly()
    else:
        asyncio.run(run_knowledge_graph_build())
