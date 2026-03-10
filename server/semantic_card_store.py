#!/usr/bin/env python3
"""
XREAL Semantic Card Store — L3 시맨틱 카드 (ChromaDB 하이브리드 검색)

Mem0와 별도로, 원본 에피소드/관찰을 임베딩하여 저장.
벡터 유사도 + 메타데이터 필터링(날짜, 타입, 사람, 장소, 감정 등) 하이브리드 검색.

Mem0와의 차이:
  - Mem0: LLM이 추출한 "사실" 저장 (압축됨, A.U.D.N.)
  - SemanticCardStore: 원본 에피소드를 임베딩으로 저장 (원문 보존, 메타 필터링)

Usage:
    pip install chromadb sentence-transformers
"""

import os
import time
import json
import logging
from typing import Optional
from datetime import datetime

import chromadb
from chromadb.config import Settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
log = logging.getLogger("semantic_card_store")

# ─── Config ───

CHROMA_PATH = os.getenv("CHROMA_CARD_PATH", "./data/chroma_cards")
COLLECTION_NAME = os.getenv("CHROMA_CARD_COLLECTION", "xreal_semantic_cards")
EMBED_MODEL = os.getenv("CARD_EMBED_MODEL", "sentence-transformers/all-MiniLM-L6-v2")


class SemanticCardStore:
    """L3 시맨틱 카드 — ChromaDB 하이브리드 검색"""

    def __init__(self, chroma_path: str = None, collection_name: str = None,
                 embed_model: str = None):
        self.chroma_path = chroma_path or CHROMA_PATH
        self.collection_name = collection_name or COLLECTION_NAME
        self.embed_model_name = embed_model or EMBED_MODEL

        # ChromaDB 클라이언트 (영속 모드)
        self.client = chromadb.PersistentClient(
            path=self.chroma_path,
            settings=Settings(anonymized_telemetry=False),
        )

        # 임베딩 함수 (sentence-transformers 로컬)
        from chromadb.utils.embedding_functions import SentenceTransformerEmbeddingFunction
        self.embed_fn = SentenceTransformerEmbeddingFunction(
            model_name=self.embed_model_name,
        )

        # 컬렉션 생성/가져오기
        self.collection = self.client.get_or_create_collection(
            name=self.collection_name,
            embedding_function=self.embed_fn,
            metadata={"hnsw:space": "cosine"},  # 코사인 유사도
        )

        log.info(f"SemanticCardStore 초기화: {self.chroma_path}, "
                 f"모델={self.embed_model_name}, "
                 f"기존 카드={self.collection.count()}장")

    # ─── 저장 ───

    def add_card(self, card_id: str, content: str,
                 event_type: str = "observation",
                 user_id: str = "teacher",
                 person: str = None,
                 location: str = None,
                 emotion: str = None,
                 tags: list[str] = None,
                 extra_metadata: dict = None) -> str:
        """
        시맨틱 카드 추가.

        메타데이터 필드:
          - event_type: vision, speech, sensor, interaction, reflection, observation
          - user_id: 사용자/관찰 대상
          - person: 관련 인물 이름
          - location: 장소명
          - emotion: 감정 (positive, negative, neutral, mixed)
          - tags: 태그 목록 (쉼표 구분 문자열로 저장)
          - date: YYYY-MM-DD
          - hour: 시간대 (0-23)
          - timestamp: Unix timestamp
        """
        now = time.time()
        dt = datetime.fromtimestamp(now)

        metadata = {
            "event_type": event_type,
            "user_id": user_id,
            "date": dt.strftime("%Y-%m-%d"),
            "hour": dt.hour,
            "timestamp": now,
        }
        if person:
            metadata["person"] = person
        if location:
            metadata["location"] = location
        if emotion:
            metadata["emotion"] = emotion
        if tags:
            metadata["tags"] = ",".join(tags)
        if extra_metadata:
            for k, v in extra_metadata.items():
                if isinstance(v, (str, int, float, bool)):
                    metadata[k] = v

        self.collection.upsert(
            ids=[card_id],
            documents=[content],
            metadatas=[metadata],
        )
        log.info(f"카드 추가: {card_id} [{event_type}] ({len(content)}자)")
        return card_id

    def add_episode_as_card(self, episode: dict) -> str:
        """에피소드 dict를 시맨틱 카드로 변환하여 저장"""
        ts = episode.get("timestamp", time.time())
        card_id = f"ep_{int(ts * 1000)}"

        metadata = episode.get("metadata", {}) or {}
        return self.add_card(
            card_id=card_id,
            content=episode.get("content", ""),
            event_type=episode.get("event_type", "observation"),
            person=metadata.get("person"),
            location=metadata.get("location"),
            emotion=metadata.get("emotion"),
            tags=metadata.get("tags"),
            extra_metadata={k: v for k, v in metadata.items()
                           if k not in ("person", "location", "emotion", "tags")},
        )

    # ─── 하이브리드 검색 ───

    def search(self, query: str, n_results: int = 10,
               event_type: str = None,
               user_id: str = None,
               person: str = None,
               location: str = None,
               emotion: str = None,
               date: str = None,
               date_from: str = None,
               date_to: str = None,
               hour_min: int = None,
               hour_max: int = None,
               has_tag: str = None) -> list[dict]:
        """
        하이브리드 검색: 벡터 유사도 + 메타데이터 필터링.

        Args:
            query: 시맨틱 검색 쿼리 (자연어)
            n_results: 최대 결과 수
            event_type: 이벤트 타입 필터 (vision, speech 등)
            user_id: 사용자 필터
            person: 관련 인물 필터
            location: 장소 필터
            emotion: 감정 필터
            date: 특정 날짜 (YYYY-MM-DD)
            date_from/date_to: 날짜 범위
            hour_min/hour_max: 시간대 범위
            has_tag: 특정 태그 포함

        Returns:
            [{id, content, metadata, distance}, ...]
        """
        where_clauses = []

        if event_type:
            where_clauses.append({"event_type": event_type})
        if user_id:
            where_clauses.append({"user_id": user_id})
        if person:
            where_clauses.append({"person": person})
        if location:
            where_clauses.append({"location": location})
        if emotion:
            where_clauses.append({"emotion": emotion})
        if date:
            where_clauses.append({"date": date})
        if date_from:
            where_clauses.append({"date": {"$gte": date_from}})
        if date_to:
            where_clauses.append({"date": {"$lte": date_to}})
        if hour_min is not None:
            where_clauses.append({"hour": {"$gte": hour_min}})
        if hour_max is not None:
            where_clauses.append({"hour": {"$lte": hour_max}})

        # where 구성
        where = None
        if len(where_clauses) == 1:
            where = where_clauses[0]
        elif len(where_clauses) > 1:
            where = {"$and": where_clauses}

        # where_document (태그는 document가 아니라 metadata에 쉼표 구분으로 저장)
        where_document = None
        if has_tag:
            # tags 메타데이터에서 검색 (쉼표 구분)
            where_clauses_with_tag = where_clauses.copy()
            where_clauses_with_tag.append({"tags": {"$contains": has_tag}})
            if len(where_clauses_with_tag) == 1:
                where = where_clauses_with_tag[0]
            else:
                where = {"$and": where_clauses_with_tag}

        # 쿼리 실행
        try:
            results = self.collection.query(
                query_texts=[query],
                n_results=min(n_results, self.collection.count() or 1),
                where=where,
                where_document=where_document,
            )
        except Exception as e:
            log.error(f"검색 오류: {e}")
            # 필터 없이 재시도
            try:
                results = self.collection.query(
                    query_texts=[query],
                    n_results=min(n_results, self.collection.count() or 1),
                )
            except Exception as e2:
                log.error(f"검색 재시도 실패: {e2}")
                return []

        # 결과 포맷팅
        cards = []
        if results and results.get("ids") and results["ids"][0]:
            for i, card_id in enumerate(results["ids"][0]):
                card = {
                    "id": card_id,
                    "content": results["documents"][0][i] if results.get("documents") else "",
                    "metadata": results["metadatas"][0][i] if results.get("metadatas") else {},
                    "distance": results["distances"][0][i] if results.get("distances") else None,
                }
                cards.append(card)

        log.info(f"검색: '{query[:50]}' → {len(cards)}건 (필터: {len(where_clauses)}개)")
        return cards

    def search_by_metadata(self, n_results: int = 20, **filters) -> list[dict]:
        """메타데이터만으로 검색 (벡터 유사도 없이)"""
        where_clauses = []
        for key, value in filters.items():
            if value is not None:
                where_clauses.append({key: value})

        where = None
        if len(where_clauses) == 1:
            where = where_clauses[0]
        elif len(where_clauses) > 1:
            where = {"$and": where_clauses}

        try:
            results = self.collection.get(
                where=where,
                limit=n_results,
            )
        except Exception as e:
            log.error(f"메타데이터 검색 오류: {e}")
            return []

        cards = []
        if results and results.get("ids"):
            for i, card_id in enumerate(results["ids"]):
                card = {
                    "id": card_id,
                    "content": results["documents"][i] if results.get("documents") else "",
                    "metadata": results["metadatas"][i] if results.get("metadatas") else {},
                }
                cards.append(card)

        return cards

    # ─── 관리 ───

    def get_card(self, card_id: str) -> Optional[dict]:
        """특정 카드 조회"""
        try:
            result = self.collection.get(ids=[card_id])
            if result and result["ids"]:
                return {
                    "id": result["ids"][0],
                    "content": result["documents"][0],
                    "metadata": result["metadatas"][0],
                }
        except Exception:
            pass
        return None

    def delete_card(self, card_id: str) -> bool:
        """카드 삭제"""
        try:
            self.collection.delete(ids=[card_id])
            return True
        except Exception as e:
            log.error(f"삭제 실패: {e}")
            return False

    def count(self) -> int:
        """총 카드 수"""
        return self.collection.count()

    def get_stats(self) -> dict:
        """통계"""
        total = self.count()
        if total == 0:
            return {"total": 0, "by_type": {}, "by_date": {}}

        # 타입별 카운트 (전체 가져와서 집계)
        try:
            all_meta = self.collection.get(limit=total)
            type_counts = {}
            date_counts = {}
            for meta in (all_meta.get("metadatas") or []):
                etype = meta.get("event_type", "unknown")
                type_counts[etype] = type_counts.get(etype, 0) + 1
                date = meta.get("date", "unknown")
                date_counts[date] = date_counts.get(date, 0) + 1
        except Exception:
            type_counts = {}
            date_counts = {}

        return {
            "total": total,
            "by_type": type_counts,
            "by_date": dict(sorted(date_counts.items(), reverse=True)[:7]),
        }


# ─── Standalone test ───

if __name__ == "__main__":
    print("=== Semantic Card Store 테스트 ===")
    store = SemanticCardStore(chroma_path="./data/test_chroma_cards")
    print(f"기존 카드: {store.count()}장\n")

    # 테스트 카드 추가
    print("--- 카드 추가 ---")
    store.add_card(
        card_id="test_001",
        content="오늘 수학 시간에 민수가 집중을 잘 했다. 2주간 개선 추세가 보인다.",
        event_type="observation",
        person="민수",
        emotion="positive",
        tags=["수학", "집중력", "개선"],
    )
    store.add_card(
        card_id="test_002",
        content="운동장에서 영희가 넘어졌다. 무릎이 약간 까졌지만 곧 일어났다.",
        event_type="vision",
        person="영희",
        location="운동장",
        emotion="negative",
        tags=["부상", "운동장"],
    )
    store.add_card(
        card_id="test_003",
        content="점심시간에 반 전체가 급식을 먹었다. 메뉴는 비빔밥이었고 대부분 잘 먹었다.",
        event_type="observation",
        location="급식실",
        emotion="neutral",
        tags=["점심", "급식"],
    )
    print(f"총 {store.count()}장\n")

    # 하이브리드 검색 테스트
    print("--- 시맨틱 검색: '학생 행동' ---")
    results = store.search("학생 행동 관찰")
    for r in results:
        print(f"  [{r['distance']:.3f}] {r['content'][:80]}")
        print(f"         메타: {r['metadata'].get('event_type')}, "
              f"인물={r['metadata'].get('person')}, "
              f"감정={r['metadata'].get('emotion')}")

    print("\n--- 하이브리드 검색: 감정=positive ---")
    results = store.search("학생 관찰", emotion="positive")
    for r in results:
        print(f"  [{r['distance']:.3f}] {r['content'][:80]}")

    print("\n--- 메타데이터만 검색: person=민수 ---")
    results = store.search_by_metadata(person="민수")
    for r in results:
        print(f"  {r['content'][:80]}")

    print("\n--- 통계 ---")
    stats = store.get_stats()
    print(f"  총: {stats['total']}장, 타입별: {stats['by_type']}")

    print("\n[OK] 모든 테스트 완료")
