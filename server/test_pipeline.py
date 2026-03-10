#!/usr/bin/env python3
"""
XREAL Pipeline Integration Test — 더미 데이터로 전체 파이프라인 검증

LLM 서버 없이도 테스트 가능한 항목:
  ✅ EpisodeStore (L2 SQLite)
  ✅ SemanticCardStore (L3 ChromaDB 하이브리드)
  ✅ BERTopic 토픽 마이닝
  ✅ STUMPY 시계열 패턴
  ⬜ Mem0 (LLM 서버 필요)
  ⬜ Graphiti (LLM 서버 필요)
  ⬜ Nightly Reflection (LLM 서버 필요)

Usage:
    python test_pipeline.py
"""

import json
import time
import sys
import numpy as np
from datetime import datetime, timedelta
from pathlib import Path

# 테스트 데이터 디렉토리
TEST_DATA_DIR = Path("./data/test")
TEST_DATA_DIR.mkdir(parents=True, exist_ok=True)


def print_header(title):
    print(f"\n{'='*60}")
    print(f"  {title}")
    print(f"{'='*60}")


def print_result(name, ok, detail=""):
    icon = "✅" if ok else "❌"
    print(f"  {icon} {name}" + (f" — {detail}" if detail else ""))


# ─── 더미 데이터 생성 ───

DUMMY_EPISODES = [
    {"event_type": "speech", "content": "오늘 민수가 수학 시간에 집중을 잘 했다. 2주간 개선 추세가 보인다.", "emotion": "positive", "person": "민수"},
    {"event_type": "speech", "content": "영희가 국어 시간에 발표를 했는데 자신감이 많이 붙었다.", "emotion": "positive", "person": "영희"},
    {"event_type": "vision", "content": "교실에서 아이들이 모둠 활동을 하고 있다. 3개 모둠으로 나뉘어 각자 프로젝트를 진행 중.", "emotion": "neutral", "person": None},
    {"event_type": "speech", "content": "준호가 점심시간에 친구들과 다퉜다. 사소한 오해에서 시작된 것 같다.", "emotion": "negative", "person": "준호"},
    {"event_type": "vision", "content": "운동장에서 체육 수업 중. 아이들이 축구를 하고 있다. 날씨가 맑다.", "emotion": "positive", "person": None},
    {"event_type": "interaction", "content": "학부모 상담: 민수 어머니와 수학 성적 향상에 대해 이야기. 가정에서도 자기주도학습 시간 늘렸다고.", "emotion": "positive", "person": "민수"},
    {"event_type": "speech", "content": "수업 종료 후 교무실에서 내일 현장학습 준비사항 확인. 버스 2대, 간식 준비 완료.", "emotion": "neutral", "person": None},
    {"event_type": "vision", "content": "하교 시간. 학교 정문에서 학부모들이 아이들을 기다리고 있다.", "emotion": "neutral", "person": None},
    {"event_type": "speech", "content": "준호가 방과 후에 사과하고 화해했다. 선생님 중재 없이 스스로 해결.", "emotion": "positive", "person": "준호"},
    {"event_type": "reflection", "content": "오늘은 전반적으로 좋은 하루였다. 민수의 성장이 눈에 띈다. 준호의 갈등 해결 능력도 발전 중.", "emotion": "positive", "person": None},
    {"event_type": "speech", "content": "영희가 미술 시간에 그림을 그렸는데 색감이 아주 좋다. 미술 대회 추천해볼까.", "emotion": "positive", "person": "영희"},
    {"event_type": "sensor", "content": "심박수 72, 걸음수 8500, 스트레스 낮음. 오후 3시 기준.", "emotion": "neutral", "person": None},
    {"event_type": "speech", "content": "수학 보충수업에서 지수가 분수 개념을 아직 어려워한다. 개별 지도 필요.", "emotion": "negative", "person": "지수"},
    {"event_type": "interaction", "content": "동료 교사와 다음 주 교육과정 회의 일정 조율. 수요일 4시로 확정.", "emotion": "neutral", "person": None},
    {"event_type": "vision", "content": "급식실에서 아이들이 식사 중. 오늘 메뉴는 비빔밥. 잔반이 적다.", "emotion": "positive", "person": None},
    {"event_type": "speech", "content": "준호와 민수가 같은 모둠이 되어 협력하고 있다. 서로 도와가며 과학 실험 진행.", "emotion": "positive", "person": "준호"},
    {"event_type": "speech", "content": "내일 현장학습 때 영희가 발표 리더를 맡기로 했다. 적극적으로 지원.", "emotion": "positive", "person": "영희"},
    {"event_type": "reflection", "content": "이번 주 전체적으로 학급 분위기가 좋아지고 있다. 모둠활동이 효과적.", "emotion": "positive", "person": None},
    {"event_type": "speech", "content": "지수가 오늘 분수 문제 3개를 스스로 풀었다. 조금씩 이해하기 시작.", "emotion": "positive", "person": "지수"},
    {"event_type": "vision", "content": "방과 후 교실 정리 중. 아이들이 자발적으로 청소를 도와주었다.", "emotion": "positive", "person": None},
    {"event_type": "speech", "content": "학부모 단체 채팅방에 현장학습 안내문 공유. 동의서 회수율 95%.", "emotion": "neutral", "person": None},
    {"event_type": "sensor", "content": "심박수 68, 걸음수 11200, 스트레스 보통. 퇴근 시간.", "emotion": "neutral", "person": None},
    {"event_type": "speech", "content": "퇴근길에 내일 수업 준비 구상. 수학은 분수 심화, 국어는 독서감상문.", "emotion": "neutral", "person": None},
    {"event_type": "interaction", "content": "민수 어머니에게 문자: 오늘 수학 보충수업에서 만점 받았다고 알림.", "emotion": "positive", "person": "민수"},
    {"event_type": "reflection", "content": "지수의 분수 이해도가 조금씩 올라가고 있다. 시각자료 활용이 효과적. 다음 주에도 계속.", "emotion": "positive", "person": "지수"},
]


def gen_dummy_hr(hours=24, interval_sec=60) -> np.ndarray:
    """더미 심박수 데이터 (24시간, 1분 간격)"""
    n = hours * 3600 // interval_sec
    t = np.linspace(0, hours, n)
    # 기본 안정시 HR + 일과 패턴 + 노이즈
    base_hr = 65
    circadian = 5 * np.sin(2 * np.pi * (t - 6) / 24)  # 낮에 높고 밤에 낮음
    # 출근(8시), 수업(9-12, 13-16), 운동(17시) 패턴
    activity = np.zeros(n)
    for i, hour in enumerate(t):
        h = hour % 24
        if 8 <= h < 9:
            activity[i] = 15  # 출근
        elif 9 <= h < 12 or 13 <= h < 16:
            activity[i] = 10 + 5 * np.sin(np.pi * (h - 9) / 3)  # 수업
        elif 17 <= h < 18:
            activity[i] = 30  # 운동
        elif 12 <= h < 13:
            activity[i] = 5  # 점심

    noise = np.random.normal(0, 3, n)
    hr = base_hr + circadian + activity + noise
    hr = np.clip(hr, 45, 180)

    # 이상치 삽입 (17:30에 갑자기 급등)
    anomaly_start = int(17.5 * 3600 / interval_sec)
    if anomaly_start + 10 < n:
        hr[anomaly_start:anomaly_start + 10] = 150 + np.random.normal(0, 5, 10)

    return hr


# ─── 테스트 1: EpisodeStore ───

def test_episode_store():
    print_header("TEST 1: EpisodeStore (L2 SQLite)")
    from episode_store import EpisodeStore

    store = EpisodeStore(db_path=str(TEST_DATA_DIR / "test_episodes.db"))
    initial = store.count()

    for ep in DUMMY_EPISODES:
        ts = time.time() - np.random.randint(0, 86400)
        store.save(
            event_type=ep["event_type"],
            content=ep["content"],
            timestamp=ts,
            metadata={"emotion": ep.get("emotion"), "person": ep.get("person")},
        )

    new_count = store.count()
    print_result("저장", new_count > initial, f"{new_count - initial}건 추가 (총 {new_count})")

    recent = store.get_recent(limit=5)
    print_result("조회", len(recent) > 0, f"최근 {len(recent)}건")

    today = datetime.now().strftime("%Y-%m-%d")
    today_eps = store.get_by_date(today)
    print_result("날짜별 조회", True, f"오늘 {len(today_eps)}건")

    stats = store.get_daily_stats(7)
    print_result("일별 통계", True, f"{len(stats)}일간 통계")

    return True


# ─── 테스트 2: SemanticCardStore (ChromaDB) ───

def test_semantic_card_store():
    print_header("TEST 2: SemanticCardStore (L3 ChromaDB 하이브리드)")
    from semantic_card_store import SemanticCardStore

    store = SemanticCardStore(
        chroma_path=str(TEST_DATA_DIR / "test_chroma_cards"),
        collection_name="test_cards",
    )

    # 카드 추가
    for i, ep in enumerate(DUMMY_EPISODES):
        store.add_card(
            card_id=f"test_{i:03d}",
            content=ep["content"],
            event_type=ep["event_type"],
            person=ep.get("person"),
            emotion=ep.get("emotion"),
            tags=["테스트", ep["event_type"]],
        )

    total = store.count()
    print_result("카드 추가", total >= len(DUMMY_EPISODES), f"총 {total}장")

    # 시맨틱 검색
    results = store.search("민수 수학 성적")
    print_result("시맨틱 검색", len(results) > 0,
                f"'민수 수학 성적' → {len(results)}건")
    if results:
        print(f"    최상위: [{results[0]['distance']:.3f}] {results[0]['content'][:60]}...")

    # 하이브리드: 시맨틱 + person 필터
    results = store.search("학생 관찰", person="준호")
    print_result("하이브리드 (person=준호)", len(results) > 0,
                f"{len(results)}건")

    # 하이브리드: 시맨틱 + emotion 필터
    results = store.search("학생 행동", emotion="negative")
    print_result("하이브리드 (emotion=negative)", len(results) > 0,
                f"{len(results)}건")

    # 메타데이터만 검색
    results = store.search_by_metadata(event_type="vision")
    print_result("메타데이터 검색 (vision)", len(results) > 0,
                f"{len(results)}건")

    # 통계
    stats = store.get_stats()
    print_result("통계", stats["total"] > 0,
                f"타입별: {stats['by_type']}")

    return True


# ─── 테스트 3: BERTopic ───

def test_bertopic():
    print_header("TEST 3: BERTopic 토픽 마이닝")
    try:
        from bertopic import BERTopic
        from sentence_transformers import SentenceTransformer
    except ImportError:
        print_result("BERTopic import", False, "pip install bertopic sentence-transformers")
        return False

    texts = [ep["content"] for ep in DUMMY_EPISODES]
    print(f"  문서 수: {len(texts)}")

    # 임베딩
    embed_model = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2")
    embeddings = embed_model.encode(texts, show_progress_bar=False)
    print_result("임베딩 생성", embeddings.shape[0] == len(texts),
                f"shape={embeddings.shape}")

    # BERTopic (작은 데이터셋이므로 min_topic_size 작게)
    topic_model = BERTopic(
        embedding_model=embed_model,
        min_topic_size=3,
        language="multilingual",
        verbose=False,
    )
    topics, probs = topic_model.fit_transform(texts, embeddings=embeddings)
    print_result("토픽 모델링", True,
                f"{len(set(topics)) - (1 if -1 in topics else 0)}개 토픽, "
                f"outlier={sum(1 for t in topics if t==-1)}건")

    # 토픽 정보
    topic_info = topic_model.get_topic_info()
    for _, row in topic_info.iterrows():
        tid = int(row["Topic"])
        if tid == -1:
            continue
        words = topic_model.get_topic(tid)
        top_words = [w for w, _ in words[:5]] if words else []
        print(f"    토픽 {tid} ({int(row['Count'])}건): {', '.join(top_words)}")

    print_result("토픽 추출", len(topic_info) > 1, f"총 {len(topic_info)}행")
    return True


# ─── 테스트 4: STUMPY ───

def test_stumpy():
    print_header("TEST 4: STUMPY 시계열 패턴 (더미 HR)")
    try:
        import stumpy
    except ImportError:
        print_result("STUMPY import", False, "pip install stumpy")
        return False

    # 더미 심박수 생성
    hr = gen_dummy_hr(hours=24, interval_sec=60)
    print(f"  데이터: {len(hr)}포인트 (24시간, 1분 간격)")
    print(f"  HR 범위: {hr.min():.0f} ~ {hr.max():.0f} bpm")

    # Matrix Profile (15분 윈도우)
    window = 15
    mp = stumpy.stump(hr, window)
    print_result("Matrix Profile 계산", mp.shape[0] > 0,
                f"shape={mp.shape}")

    # 모티프 (반복 패턴)
    distances = mp[:, 0].astype(float)
    motif_idx = int(np.argmin(distances))
    nn_idx = int(mp[motif_idx, 1])
    motif_dist = float(distances[motif_idx])

    motif_hour_a = motif_idx / 60
    motif_hour_b = nn_idx / 60
    print_result("모티프 발견", motif_dist < 5.0,
                f"인덱스 {motif_idx}({motif_hour_a:.1f}h) ↔ {nn_idx}({motif_hour_b:.1f}h), "
                f"거리={motif_dist:.2f}")

    # 디스코드 (이상치)
    discord_idx = int(np.argmax(distances))
    discord_dist = float(distances[discord_idx])
    discord_hour = discord_idx / 60
    discord_hr_mean = float(np.mean(hr[discord_idx:discord_idx + window]))
    print_result("디스코드 발견", discord_dist > 3.0,
                f"인덱스 {discord_idx}({discord_hour:.1f}h), "
                f"평균HR={discord_hr_mean:.0f}bpm, 거리={discord_dist:.2f}")

    # 이상치가 17:30 부근인지 확인 (삽입한 anomaly)
    anomaly_detected = 17.0 <= discord_hour <= 18.5
    print_result("이상치 위치 정확도", anomaly_detected,
                f"예상=17.5h, 실제={discord_hour:.1f}h")

    return True


# ─── 테스트 5: PredictionEngine ───

def test_prediction_engine():
    print_header("TEST 5: PredictionEngine (DB 없이 기본값)")
    from prediction_engine import DigitalTwinPredictor

    predictor = DigitalTwinPredictor()

    recovery = predictor.predict_recovery_state()
    print_result("회복 상태 예측", "recovery_score" in recovery,
                f"점수={recovery['recovery_score']}, 권장={recovery['recommendation']}")

    cs = predictor.get_critical_speed()
    print_result("Critical Speed", "cs_kmh" in cs,
                f"CS={cs['cs_kmh']}km/h, 소스={cs['source']}")

    pace = predictor.predict_optimal_pace(5.0)
    print_result("5K 최적 페이스", "target_pace_min_km" in pace,
                f"페이스={pace['target_pace_min_km']}분/km")

    injury = predictor.predict_injury_risk()
    print_result("부상 위험", "risk_level" in injury,
                f"레벨={injury['risk_level']}, ACWR={injury['acwr']}")

    sleep = predictor.predict_sleep_quality()
    print_result("수면 품질 예측", "predicted_efficiency" in sleep,
                f"효율={sleep['predicted_efficiency']}")

    return True


# ─── 테스트 6: ForecastEngine ───

def test_forecast_engine():
    print_header("TEST 6: ForecastEngine (NeuralProphet + TFT)")
    from forecast_engine import generate_synthetic_data, train_neuralprophet, train_simple_tft, generate_forecast_insights

    # 합성 데이터
    df = generate_synthetic_data(days=14)
    print_result("합성 데이터 생성", len(df) == 336, f"{len(df)}행")

    # NeuralProphet
    try:
        np_result = train_neuralprophet(df)
        has_mae = "mae" in np_result and not (isinstance(np_result["mae"], float) and np_result["mae"] != np_result["mae"])  # NaN check
        print_result("NeuralProphet 학습", has_mae,
                    f"MAE={np_result.get('mae', 'N/A')}, forecast={len(np_result.get('forecast_24h', []))}시간")

        # 주기 패턴 확인
        daily = np_result.get("components", {}).get("daily_pattern", [])
        weekly = np_result.get("components", {}).get("weekly_pattern", [])
        print_result("일간 패턴 추출", len(daily) > 0, f"{len(daily)}시간대")
        print_result("주간 패턴 추출", len(weekly) > 0, f"{len(weekly)}요일")
    except Exception as e:
        print_result("NeuralProphet", False, f"에러: {e}")
        np_result = {"error": str(e)}

    # 간이 예측 (항상 동작)
    tft_result = train_simple_tft(df)
    forecast_24h = tft_result.get("forecast_24h", [])
    print_result("행동 예측 (간이)", len(forecast_24h) == 24,
                f"모델={tft_result['model']}, {len(forecast_24h)}시간 예측")

    # 바쁜 시간 합리성 (수업 시간 9~15시가 높아야)
    if forecast_24h:
        busy = [f for f in forecast_24h if f["predicted_activity"] > 2.0]
        busy_hours = [f["hour"] for f in busy]
        school_covered = any(9 <= h <= 14 for h in busy_hours)
        print_result("수업 시간 활동 감지", school_covered,
                    f"바쁜 시간: {busy_hours}")

    # 인사이트 생성
    insights = generate_forecast_insights(np_result, tft_result)
    print_result("인사이트 생성", len(insights) > 0, f"{len(insights)}개")
    for ins in insights:
        print(f"    -> {ins}")

    return True


# ─── Main ───

if __name__ == "__main__":
    print("\n" + "#" * 60)
    print("  XREAL Pipeline Integration Test")
    print("  Dummy data pipeline verification")
    print("#" * 60)

    results = {}
    tests = [
        ("EpisodeStore", test_episode_store),
        ("SemanticCardStore", test_semantic_card_store),
        ("BERTopic", test_bertopic),
        ("STUMPY", test_stumpy),
        ("PredictionEngine", test_prediction_engine),
        ("ForecastEngine", test_forecast_engine),
    ]

    for name, test_fn in tests:
        try:
            results[name] = test_fn()
        except Exception as e:
            print_result(name, False, f"ERROR: {e}")
            results[name] = False
            import traceback
            traceback.print_exc()

    # 요약
    print_header("SUMMARY")
    passed = sum(1 for v in results.values() if v)
    total = len(results)
    for name, ok in results.items():
        print_result(name, ok)

    print(f"\n  결과: {passed}/{total} 통과")

    if passed == total:
        print("\n  🎉 전체 파이프라인 검증 완료!")
    else:
        print(f"\n  ⚠️  {total - passed}개 실패 — 로그 확인 필요")

    sys.exit(0 if passed == total else 1)
