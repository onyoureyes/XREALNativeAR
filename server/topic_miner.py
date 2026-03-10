#!/usr/bin/env python3
"""
XREAL Topic Miner — BERTopic 기반 대화 토픽 클러스터링 + 감정 트렌드

에피소드/대화 로그를 BERTopic으로 분석하여:
  - 토픽 클러스터 발견 (무슨 주제가 자주 나오는가)
  - 토픽별 감정 분포 (어떤 주제가 부정적인가)
  - 시간에 따른 토픽 변화 (Dynamic Topic Modeling)
  - 주간 토픽 리포트 생성

실행 주기: 매주 (또는 수동)
입력: episode_store (L2) + semantic_card_store (L3)
출력: data/topic_reports/ + Mem0에 인사이트 저장

Usage:
    pip install bertopic sentence-transformers hdbscan umap-learn
    python topic_miner.py                # 즉시 실행
    python topic_miner.py --schedule     # 매주 일요일 새벽 4시
"""

import os
import json
import time
import logging
import argparse
from datetime import datetime, timedelta
from pathlib import Path
from collections import Counter

import httpx
import numpy as np

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("logs/topic_miner.log", encoding="utf-8"),
    ],
)
log = logging.getLogger("topic_miner")

# ─── Config ───

ORCHESTRATOR_URL = os.getenv("ORCHESTRATOR_URL", "http://127.0.0.1:8091")
EMBED_MODEL = os.getenv("TOPIC_EMBED_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
REPORT_DIR = Path(os.getenv("TOPIC_REPORT_DIR", "./data/topic_reports"))
MIN_DOCS = int(os.getenv("TOPIC_MIN_DOCS", "20"))  # 최소 문서 수


def ensure_dirs():
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    Path("logs").mkdir(exist_ok=True)


def fetch_documents(client: httpx.Client, days: int = 7) -> list[dict]:
    """에피소드 + 시맨틱 카드에서 문서 수집"""
    docs = []

    # L2 에피소드 (최근 N일)
    for day_offset in range(days):
        date = (datetime.now() - timedelta(days=day_offset)).strftime("%Y-%m-%d")
        try:
            r = client.get(f"{ORCHESTRATOR_URL}/v1/episode/history",
                          params={"date": date, "limit": 200})
            r.raise_for_status()
            episodes = r.json().get("episodes", [])
            for ep in episodes:
                if ep.get("content") and len(ep["content"]) > 10:
                    docs.append({
                        "text": ep["content"],
                        "timestamp": ep.get("timestamp", 0),
                        "event_type": ep.get("event_type", "unknown"),
                        "metadata": ep.get("metadata", {}),
                    })
        except Exception as e:
            log.warning(f"에피소드 조회 실패 ({date}): {e}")

    # L3 시맨틱 카드 (전체)
    try:
        r = client.post(f"{ORCHESTRATOR_URL}/v1/cards/search",
                       json={"query": "일상 관찰 대화", "n_results": 200})
        r.raise_for_status()
        cards = r.json().get("results", [])
        for card in cards:
            text = card.get("content", "")
            if text and len(text) > 10:
                meta = card.get("metadata", {})
                docs.append({
                    "text": text,
                    "timestamp": meta.get("timestamp", 0),
                    "event_type": meta.get("event_type", "unknown"),
                    "metadata": meta,
                })
    except Exception as e:
        log.warning(f"카드 조회 실패: {e}")

    # 중복 제거 (동일 텍스트)
    seen = set()
    unique_docs = []
    for doc in docs:
        key = doc["text"][:100]
        if key not in seen:
            seen.add(key)
            unique_docs.append(doc)

    log.info(f"문서 수집: {len(unique_docs)}건 (원본 {len(docs)}건, 중복 제거)")
    return unique_docs


def run_bertopic(documents: list[dict]) -> dict:
    """BERTopic 토픽 모델링 실행"""
    from bertopic import BERTopic
    from sentence_transformers import SentenceTransformer

    texts = [d["text"] for d in documents]
    timestamps = [datetime.fromtimestamp(d["timestamp"]) if d["timestamp"] else datetime.now()
                  for d in documents]
    emotions = [d.get("metadata", {}).get("emotion", "unknown") for d in documents]
    event_types = [d["event_type"] for d in documents]

    log.info(f"BERTopic 시작: {len(texts)}건")

    # 임베딩 생성
    embed_model = SentenceTransformer(EMBED_MODEL)
    embeddings = embed_model.encode(texts, show_progress_bar=True, batch_size=32)

    # BERTopic 실행
    topic_model = BERTopic(
        embedding_model=embed_model,
        language="multilingual",
        min_topic_size=max(3, len(texts) // 20),  # 동적 최소 토픽 크기
        nr_topics="auto",
        verbose=True,
    )

    topics, probs = topic_model.fit_transform(texts, embeddings=embeddings)

    # 토픽 정보 수집
    topic_info = topic_model.get_topic_info()
    log.info(f"발견된 토픽: {len(topic_info) - 1}개 (outlier 제외)")

    # 토픽별 감정 분포
    topic_emotions = {}
    topic_event_types = {}
    for i, topic_id in enumerate(topics):
        if topic_id == -1:
            continue
        tid = str(topic_id)
        if tid not in topic_emotions:
            topic_emotions[tid] = Counter()
            topic_event_types[tid] = Counter()
        topic_emotions[tid][emotions[i]] += 1
        topic_event_types[tid][event_types[i]] += 1

    # 결과 구성
    result = {
        "date": datetime.now().strftime("%Y-%m-%d"),
        "total_documents": len(texts),
        "total_topics": len(topic_info) - 1,  # -1은 outlier
        "outlier_count": int((np.array(topics) == -1).sum()),
        "topics": [],
    }

    for _, row in topic_info.iterrows():
        tid = int(row["Topic"])
        if tid == -1:
            continue

        topic_words = topic_model.get_topic(tid)
        top_words = [w for w, _ in topic_words[:10]] if topic_words else []

        # 대표 문서 (상위 3개)
        topic_docs_idx = [i for i, t in enumerate(topics) if t == tid]
        representative_docs = [texts[i][:200] for i in topic_docs_idx[:3]]

        emotion_dist = dict(topic_emotions.get(str(tid), {}))
        event_dist = dict(topic_event_types.get(str(tid), {}))

        # 감정 점수 (-1 ~ +1)
        pos = emotion_dist.get("positive", 0)
        neg = emotion_dist.get("negative", 0)
        total_emo = pos + neg + emotion_dist.get("neutral", 0) + emotion_dist.get("mixed", 0)
        sentiment_score = (pos - neg) / max(1, total_emo)

        result["topics"].append({
            "topic_id": tid,
            "count": int(row["Count"]),
            "keywords": top_words,
            "representative_docs": representative_docs,
            "emotion_distribution": emotion_dist,
            "event_type_distribution": event_dist,
            "sentiment_score": round(sentiment_score, 3),
        })

    # 시간별 토픽 변화 (Dynamic Topic Modeling)
    try:
        topics_over_time = topic_model.topics_over_time(texts, timestamps, nr_bins=7)
        time_series = []
        for _, row in topics_over_time.iterrows():
            time_series.append({
                "topic": int(row["Topic"]),
                "timestamp": str(row["Timestamp"]),
                "frequency": int(row["Frequency"]),
                "words": row.get("Words", ""),
            })
        result["topics_over_time"] = time_series
    except Exception as e:
        log.warning(f"시간별 토픽 분석 실패: {e}")
        result["topics_over_time"] = []

    return result


def generate_insights(report: dict) -> list[str]:
    """토픽 리포트에서 인사이트 추출"""
    insights = []
    topics = report.get("topics", [])

    if not topics:
        return ["이번 주 분석할 토픽이 충분하지 않음"]

    # 가장 빈번한 토픽
    top_topic = max(topics, key=lambda t: t["count"])
    insights.append(
        f"이번 주 가장 빈번한 토픽: {', '.join(top_topic['keywords'][:5])} "
        f"({top_topic['count']}건)"
    )

    # 가장 부정적인 토픽
    neg_topics = [t for t in topics if t["sentiment_score"] < -0.2]
    if neg_topics:
        worst = min(neg_topics, key=lambda t: t["sentiment_score"])
        insights.append(
            f"[주의] 부정 감정 토픽: {', '.join(worst['keywords'][:5])} "
            f"(감정점수: {worst['sentiment_score']:.2f})"
        )

    # 가장 긍정적인 토픽
    pos_topics = [t for t in topics if t["sentiment_score"] > 0.2]
    if pos_topics:
        best = max(pos_topics, key=lambda t: t["sentiment_score"])
        insights.append(
            f"긍정 감정 토픽: {', '.join(best['keywords'][:5])} "
            f"(감정점수: {best['sentiment_score']:.2f})"
        )

    # 토픽 다양성
    total_docs = report["total_documents"]
    total_topics = report["total_topics"]
    outlier_pct = report["outlier_count"] / max(1, total_docs) * 100
    insights.append(
        f"토픽 다양성: {total_topics}개 토픽 / {total_docs}건 문서, "
        f"미분류 {outlier_pct:.0f}%"
    )

    return insights


def save_report(report: dict, insights: list[str]):
    """리포트 저장"""
    date = report["date"]
    path = REPORT_DIR / f"topic_report_{date}.json"

    report["insights"] = insights
    report["generated_at"] = datetime.now().isoformat()

    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    log.info(f"리포트 저장: {path}")
    return path


def save_to_mem0(client: httpx.Client, insights: list[str], date: str):
    """인사이트를 Mem0에 저장"""
    saved = 0
    for insight in insights:
        try:
            client.post(
                f"{ORCHESTRATOR_URL}/v1/mem0/add",
                json={
                    "content": f"[주간 토픽 분석 {date}] {insight}",
                    "user_id": "teacher",
                    "event_type": "reflection",
                    "extra_context": "BERTopic 주간 분석",
                },
            )
            saved += 1
        except Exception as e:
            log.warning(f"Mem0 저장 실패: {e}")
    log.info(f"Mem0에 인사이트 {saved}건 저장")


def run_topic_mining():
    """메인 토픽 마이닝"""
    log.info("=" * 60)
    log.info(f"토픽 마이닝 시작: {datetime.now().isoformat()}")
    log.info("=" * 60)

    ensure_dirs()

    with httpx.Client(timeout=httpx.Timeout(60.0, connect=5.0)) as client:
        # 1. 문서 수집
        documents = fetch_documents(client, days=7)

        if len(documents) < MIN_DOCS:
            log.warning(f"문서 부족 ({len(documents)}건 < {MIN_DOCS}건). "
                       f"데이터가 더 쌓인 후 실행하세요.")
            return

        # 2. BERTopic 실행
        report = run_bertopic(documents)

        # 3. 인사이트 추출
        insights = generate_insights(report)
        for ins in insights:
            log.info(f"  인사이트: {ins}")

        # 4. 리포트 저장
        save_report(report, insights)

        # 5. Mem0에 인사이트 저장
        save_to_mem0(client, insights, report["date"])

    log.info("=" * 60)
    log.info(f"토픽 마이닝 완료: {report['total_topics']}개 토픽, "
             f"{len(insights)}개 인사이트")
    log.info("=" * 60)


def schedule_weekly():
    """매주 일요일 새벽 4시 실행"""
    import sched
    import time as time_module

    scheduler = sched.scheduler(time_module.time, time_module.sleep)

    def next_sunday_4am() -> float:
        now = datetime.now()
        days_until_sunday = (6 - now.weekday()) % 7
        if days_until_sunday == 0 and now.hour >= 4:
            days_until_sunday = 7
        target = (now + timedelta(days=days_until_sunday)).replace(
            hour=4, minute=0, second=0, microsecond=0
        )
        return (target - now).total_seconds()

    def scheduled_run():
        try:
            run_topic_mining()
        except Exception as e:
            log.error(f"토픽 마이닝 에러: {e}", exc_info=True)
        delay = next_sunday_4am()
        scheduler.enter(delay, 1, scheduled_run)
        log.info(f"다음 실행: {delay / 3600:.1f}시간 후")

    delay = next_sunday_4am()
    log.info(f"주간 토픽 마이닝 스케줄러 시작. 첫 실행: {delay / 3600:.1f}시간 후")
    scheduler.enter(delay, 1, scheduled_run)
    scheduler.run()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="XREAL Topic Miner (BERTopic)")
    parser.add_argument("--schedule", action="store_true", help="매주 일요일 새벽 4시 스케줄")
    args = parser.parse_args()

    if args.schedule:
        schedule_weekly()
    else:
        run_topic_mining()
