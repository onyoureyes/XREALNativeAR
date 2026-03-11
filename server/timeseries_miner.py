#!/usr/bin/env python3
"""
XREAL Time Series Miner — STUMPY Matrix Profile 기반 센서 패턴/이상치 발견

센서 시계열 데이터(심박수, 보행수, 활동량 등)에서:
  - 모티프 발견: 반복되는 패턴 (예: "이 패턴 후 1시간 뒤 두통")
  - 이상치 발견: 평소와 다른 구간 (예: "갑자기 HR 급등")
  - 다변량 모티프: 여러 센서 동시 패턴

실행 주기: 매일 새벽 (nightly_reflection 이후)
입력: backup_server DB (structured_data), episode_store, DuckDB (장기 이력)
출력: data/timeseries_reports/ + Mem0 인사이트

Usage:
    pip install stumpy numpy
    python timeseries_miner.py              # 즉시 실행
    python timeseries_miner.py --schedule   # 매일 새벽 4시
"""

import os
import json
import time
import sqlite3
import logging
import argparse
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

import numpy as np

try:
    import stumpy
    HAS_STUMPY = True
except ImportError:
    HAS_STUMPY = False

import httpx

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("logs/timeseries_miner.log", encoding="utf-8"),
    ],
)
log = logging.getLogger("timeseries_miner")

# ─── Config ───

ORCHESTRATOR_URL = os.getenv("ORCHESTRATOR_URL", "http://127.0.0.1:8091")
BACKUP_DB_PATH = os.getenv("BACKUP_DB", "./backup.db")
REPORT_DIR = Path(os.getenv("TS_REPORT_DIR", "./data/timeseries_reports"))
# Matrix Profile 윈도우 크기 (분 단위 데이터 기준)
MP_WINDOW_SHORT = int(os.getenv("MP_WINDOW_SHORT", "15"))   # 15분 패턴
MP_WINDOW_LONG = int(os.getenv("MP_WINDOW_LONG", "60"))     # 1시간 패턴

# DuckDB (장기 이력, 선택)
try:
    import duckdb
    HAS_DUCKDB = True
except ImportError:
    HAS_DUCKDB = False

DUCKDB_PATH = os.getenv("DUCKDB_PATH", str(
    Path(__file__).parent.parent.parent / "antigravityProject" / "dark-perigee" /
    "running_science_lab" / "running_science.duckdb"
))


def ensure_dirs():
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    Path("logs").mkdir(exist_ok=True)


# ─── Data Collection ───

def load_hr_from_backup(days: int = 7) -> Optional[np.ndarray]:
    """backup.db에서 심박수 시계열 로드 (분 단위 리샘플링)"""
    if not Path(BACKUP_DB_PATH).exists():
        log.warning(f"backup.db 없음: {BACKUP_DB_PATH}")
        return None

    conn = sqlite3.connect(BACKUP_DB_PATH)
    try:
        cutoff = (datetime.now() - timedelta(days=days)).isoformat()
        rows = conn.execute("""
            SELECT value FROM structured_data
            WHERE domain = 'watch_heart_rate'
            AND updated_at >= ?
            ORDER BY updated_at ASC
        """, (cutoff,)).fetchall()

        if not rows:
            log.info("backup.db에 심박수 데이터 없음")
            return None

        values = []
        for row in rows:
            try:
                data = json.loads(row[0])
                hr = data.get("bpm") or data.get("heart_rate") or data.get("value")
                if hr and 30 < float(hr) < 220:
                    values.append(float(hr))
            except (json.JSONDecodeError, TypeError, ValueError):
                continue

        if len(values) < MP_WINDOW_SHORT * 2:
            log.info(f"심박수 데이터 부족: {len(values)}건")
            return None

        return np.array(values, dtype=np.float64)
    finally:
        conn.close()


def load_hr_from_duckdb(days: int = 7) -> Optional[np.ndarray]:
    """DuckDB에서 심박수 시계열 로드"""
    if not HAS_DUCKDB or not Path(DUCKDB_PATH).exists():
        return None

    conn = duckdb.connect(DUCKDB_PATH, read_only=True)
    try:
        cutoff = (datetime.now() - timedelta(days=days)).isoformat()
        rows = conn.execute("""
            SELECT heart_rate
            FROM heart_rate_log
            WHERE heart_rate > 30 AND heart_rate < 220
            AND timestamp >= ?
            ORDER BY timestamp ASC
        """, [cutoff]).fetchall()

        if len(rows) < MP_WINDOW_SHORT * 2:
            return None

        return np.array([r[0] for r in rows], dtype=np.float64)
    except Exception as e:
        log.warning(f"DuckDB HR 로드 실패: {e}")
        return None
    finally:
        conn.close()


def load_activity_from_episodes(client: httpx.Client, days: int = 7) -> Optional[np.ndarray]:
    """에피소드에서 시간대별 활동 강도 추출 (0-1)"""
    activity = np.zeros(days * 24, dtype=np.float64)

    for day_offset in range(days):
        date = (datetime.now() - timedelta(days=day_offset)).strftime("%Y-%m-%d")
        try:
            r = client.get(f"{ORCHESTRATOR_URL}/v1/episode/history",
                          params={"date": date, "limit": 500})
            r.raise_for_status()
            episodes = r.json().get("episodes", [])

            for ep in episodes:
                ts = ep.get("timestamp", 0)
                if ts <= 0:
                    continue
                dt = datetime.fromtimestamp(ts)
                hour_idx = day_offset * 24 + dt.hour
                if 0 <= hour_idx < len(activity):
                    # 이벤트 존재 = 활동
                    etype = ep.get("event_type", "")
                    weight = {
                        "vision": 0.3, "speech": 0.5, "sensor": 0.2,
                        "interaction": 0.8, "reflection": 0.1,
                    }.get(etype, 0.3)
                    activity[hour_idx] = min(1.0, activity[hour_idx] + weight)
        except Exception as e:
            log.warning(f"에피소드 조회 실패 ({date}): {e}")

    if activity.sum() == 0:
        return None
    return activity


def load_running_dynamics(days: int = 30) -> Optional[dict]:
    """DuckDB에서 러닝 역학 시계열 (GCT, VO, stiffness)"""
    if not HAS_DUCKDB or not Path(DUCKDB_PATH).exists():
        return None

    conn = duckdb.connect(DUCKDB_PATH, read_only=True)
    try:
        cutoff = (datetime.now() - timedelta(days=days)).isoformat()
        rows = conn.execute("""
            SELECT start_time, avg_gct_ms, avg_vertical_oscillation_cm,
                   avg_stiffness_kn_m, avg_flight_time_ms
            FROM running_dynamics
            WHERE start_time >= ?
            ORDER BY start_time ASC
        """, [cutoff]).fetchall()

        if len(rows) < 5:
            return None

        return {
            "timestamps": [str(r[0]) for r in rows],
            "gct": np.array([r[1] for r in rows if r[1]], dtype=np.float64),
            "vo": np.array([r[2] for r in rows if r[2]], dtype=np.float64),
            "stiffness": np.array([r[3] for r in rows if r[3]], dtype=np.float64),
            "flight_time": np.array([r[4] for r in rows if r[4]], dtype=np.float64),
        }
    except Exception as e:
        log.warning(f"DuckDB dynamics 로드 실패: {e}")
        return None
    finally:
        conn.close()


# ─── Matrix Profile Analysis ───

def find_motifs(ts: np.ndarray, window: int, top_k: int = 3) -> list[dict]:
    """시계열에서 반복 패턴(모티프) 발견"""
    if len(ts) < window * 2:
        return []

    mp = stumpy.stump(ts, window)
    distances = mp[:, 0].astype(float)

    # 모티프: 거리가 가장 작은 쌍
    motifs = []
    used_indices = set()

    sorted_idx = np.argsort(distances)
    for idx in sorted_idx:
        if len(motifs) >= top_k:
            break

        idx = int(idx)
        nn_idx = int(mp[idx, 1])

        # 이미 사용된 인덱스와 겹치면 스킵 (윈도우 크기만큼)
        if any(abs(idx - u) < window for u in used_indices):
            continue
        if any(abs(nn_idx - u) < window for u in used_indices):
            continue

        used_indices.add(idx)
        used_indices.add(nn_idx)

        motifs.append({
            "index_a": idx,
            "index_b": nn_idx,
            "distance": float(distances[idx]),
            "pattern_a": ts[idx:idx + window].tolist(),
            "pattern_b": ts[nn_idx:nn_idx + window].tolist(),
            "mean_value": float(np.mean(ts[idx:idx + window])),
        })

    return motifs


def find_discords(ts: np.ndarray, window: int, top_k: int = 3) -> list[dict]:
    """시계열에서 이상치(디스코드) 발견"""
    if len(ts) < window * 2:
        return []

    mp = stumpy.stump(ts, window)
    distances = mp[:, 0].astype(float)

    # 디스코드: 거리가 가장 큰 구간
    discords = []
    used_indices = set()

    sorted_idx = np.argsort(distances)[::-1]  # 내림차순
    for idx in sorted_idx:
        if len(discords) >= top_k:
            break

        idx = int(idx)
        if any(abs(idx - u) < window for u in used_indices):
            continue

        used_indices.add(idx)

        discords.append({
            "index": idx,
            "distance": float(distances[idx]),
            "pattern": ts[idx:idx + window].tolist(),
            "mean_value": float(np.mean(ts[idx:idx + window])),
            "max_value": float(np.max(ts[idx:idx + window])),
            "min_value": float(np.min(ts[idx:idx + window])),
        })

    return discords


def analyze_timeseries(name: str, ts: np.ndarray, window: int,
                       unit: str = "") -> dict:
    """단일 시계열 종합 분석"""
    log.info(f"  분석 중: {name} ({len(ts)}포인트, 윈도우={window})")

    motifs = find_motifs(ts, window)
    discords = find_discords(ts, window)

    return {
        "name": name,
        "length": len(ts),
        "window": window,
        "unit": unit,
        "stats": {
            "mean": float(np.mean(ts)),
            "std": float(np.std(ts)),
            "min": float(np.min(ts)),
            "max": float(np.max(ts)),
            "median": float(np.median(ts)),
        },
        "motifs": motifs,
        "discords": discords,
    }


# ─── Insight Generation ───

def generate_insights(analyses: list[dict]) -> list[str]:
    """분석 결과에서 인사이트 추출"""
    insights = []

    for analysis in analyses:
        name = analysis["name"]
        motifs = analysis.get("motifs", [])
        discords = analysis.get("discords", [])
        stats = analysis.get("stats", {})

        # 모티프 인사이트
        if motifs:
            top_motif = motifs[0]
            insights.append(
                f"[{name}] 반복 패턴 발견: 인덱스 {top_motif['index_a']}↔{top_motif['index_b']} "
                f"(거리={top_motif['distance']:.2f}, 평균={top_motif['mean_value']:.1f})"
            )

        # 디스코드 인사이트
        if discords:
            top_discord = discords[0]
            if top_discord["distance"] > stats.get("std", 1) * 2:
                insights.append(
                    f"[{name}] 이상치 감지: 인덱스 {top_discord['index']} "
                    f"(최대={top_discord['max_value']:.1f}, "
                    f"최소={top_discord['min_value']:.1f}, "
                    f"평소 평균={stats['mean']:.1f})"
                )

        # 변동성 인사이트
        cv = stats.get("std", 0) / max(0.01, stats.get("mean", 1))
        if cv > 0.3:
            insights.append(
                f"[{name}] 높은 변동성: CV={cv:.2f} (평균={stats['mean']:.1f}, "
                f"표준편차={stats['std']:.1f})"
            )

    if not insights:
        insights.append("분석 가능한 유의미한 패턴 없음 (데이터 부족 또는 안정적)")

    return insights


# ─── Main ───

def run_timeseries_mining():
    """메인 시계열 마이닝"""
    if not HAS_STUMPY:
        log.error("stumpy 미설치. pip install stumpy")
        return

    log.info("=" * 60)
    log.info(f"시계열 마이닝 시작: {datetime.now().isoformat()}")
    log.info("=" * 60)

    ensure_dirs()
    analyses = []

    with httpx.Client(timeout=httpx.Timeout(30.0, connect=5.0)) as client:
        # 1. 심박수 분석
        hr_data = load_hr_from_duckdb(days=7) or load_hr_from_backup(days=7)
        if hr_data is not None:
            log.info(f"심박수 데이터: {len(hr_data)}포인트")
            analyses.append(analyze_timeseries("심박수", hr_data, MP_WINDOW_SHORT, "bpm"))
            if len(hr_data) >= MP_WINDOW_LONG * 2:
                analyses.append(analyze_timeseries("심박수(장기)", hr_data, MP_WINDOW_LONG, "bpm"))
        else:
            log.info("심박수 데이터 없음 — 스킵")

        # 2. 활동 강도 분석
        activity_data = load_activity_from_episodes(client, days=7)
        if activity_data is not None:
            log.info(f"활동 강도 데이터: {len(activity_data)}포인트 (시간당)")
            window = min(12, len(activity_data) // 3)  # 12시간 또는 데이터의 1/3
            if window >= 4:
                analyses.append(analyze_timeseries("활동강도", activity_data, window, "0-1"))
        else:
            log.info("활동 강도 데이터 없음 — 스킵")

        # 3. 러닝 역학 분석
        dynamics = load_running_dynamics(days=30)
        if dynamics is not None:
            for metric_name, metric_key, unit in [
                ("접지시간(GCT)", "gct", "ms"),
                ("수직진동(VO)", "vo", "cm"),
                ("강성(Stiffness)", "stiffness", "kN/m"),
            ]:
                ts = dynamics.get(metric_key)
                if ts is not None and len(ts) >= 6:
                    window = min(5, len(ts) // 3)
                    if window >= 3:
                        analyses.append(analyze_timeseries(metric_name, ts, window, unit))
        else:
            log.info("러닝 역학 데이터 없음 — 스킵")

        # 4. 인사이트 생성
        insights = generate_insights(analyses)
        for ins in insights:
            log.info(f"  인사이트: {ins}")

        # 5. 리포트 저장
        report = {
            "date": datetime.now().strftime("%Y-%m-%d"),
            "generated_at": datetime.now().isoformat(),
            "analyses": [{k: v for k, v in a.items() if k != "motifs" or len(v) <= 5}
                         for a in analyses],
            "insights": insights,
            "data_sources": {
                "hr": "duckdb" if (load_hr_from_duckdb(1) is not None) else
                      "backup" if hr_data is not None else "none",
                "activity": "episodes" if activity_data is not None else "none",
                "dynamics": "duckdb" if dynamics is not None else "none",
            },
        }

        # 모티프/디스코드 패턴은 큰 배열이므로 요약만 저장
        for a in report["analyses"]:
            for motif in a.get("motifs", []):
                motif.pop("pattern_a", None)
                motif.pop("pattern_b", None)
            for discord in a.get("discords", []):
                discord.pop("pattern", None)

        report_path = REPORT_DIR / f"ts_report_{report['date']}.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        log.info(f"리포트 저장: {report_path}")

        # 6. Mem0에 인사이트 저장
        for insight in insights:
            try:
                client.post(
                    f"{ORCHESTRATOR_URL}/v1/mem0/add",
                    json={
                        "content": f"[시계열 패턴 분석] {insight}",
                        "user_id": "teacher",
                        "event_type": "reflection",
                        "extra_context": "STUMPY Matrix Profile 분석",
                    },
                )
            except Exception:
                pass

    log.info("=" * 60)
    log.info(f"시계열 마이닝 완료: {len(analyses)}개 시계열, {len(insights)}개 인사이트")
    log.info("=" * 60)


def schedule_daily():
    """매일 새벽 4:30 실행 (nightly_reflection 이후)"""
    import sched
    import time as time_module

    scheduler = sched.scheduler(time_module.time, time_module.sleep)

    def next_4_30am() -> float:
        now = datetime.now()
        target = now.replace(hour=4, minute=30, second=0, microsecond=0)
        if now >= target:
            target += timedelta(days=1)
        return (target - now).total_seconds()

    def scheduled_run():
        try:
            run_timeseries_mining()
        except Exception as e:
            log.error(f"시계열 마이닝 에러: {e}", exc_info=True)
        delay = next_4_30am()
        scheduler.enter(delay, 1, scheduled_run)
        log.info(f"다음 실행: {delay / 3600:.1f}시간 후")

    delay = next_4_30am()
    log.info(f"시계열 마이닝 스케줄러 시작. 첫 실행: {delay / 3600:.1f}시간 후")
    scheduler.enter(delay, 1, scheduled_run)
    scheduler.run()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="XREAL Time Series Miner (STUMPY)")
    parser.add_argument("--schedule", action="store_true", help="매일 새벽 4:30 스케줄")
    args = parser.parse_args()

    if args.schedule:
        schedule_daily()
    else:
        run_timeseries_mining()
