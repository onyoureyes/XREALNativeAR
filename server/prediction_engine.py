#!/usr/bin/env python3
"""
Digital Twin Prediction Engine
앱 실시간 데이터 + DuckDB 10년 이력 → 예측 모델 → 앱 되먹임

Models:
1. 회복 상태 (Recovery State) — 규칙 기반
2. 최적 페이스 (Optimal Pace) — CS 공식 + 조정 계수
3. 수면 품질 예측 (Sleep Quality) — 이동 평균 회귀
4. 부상 위험 (Injury Risk) — ACWR + 역학 편차
5. 주간 프로파일 (Weekly Profile) — 장기 기저선 종합
"""

import json
import sqlite3
import time
from datetime import datetime, timedelta
from pathlib import Path

# DuckDB import — optional (graceful fallback if not installed)
try:
    import duckdb
    HAS_DUCKDB = True
except ImportError:
    HAS_DUCKDB = False

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False


DUCKDB_PATH = str(Path(__file__).parent.parent.parent / "antigravityProject" / "dark-perigee" / "running_science_lab" / "running_science.duckdb")
APP_BACKUP_DB = str(Path(__file__).parent / "backup.db")


class DigitalTwinPredictor:
    """앱 실시간 데이터 + DuckDB 이력 → 예측 모델"""

    def __init__(self, duckdb_path: str = DUCKDB_PATH, app_db_path: str = APP_BACKUP_DB):
        self.duckdb_path = duckdb_path
        self.app_db_path = app_db_path

    # ─── DuckDB 접근 ───

    def _duck_query(self, sql: str) -> list:
        """DuckDB 쿼리 (없으면 빈 리스트)"""
        if not HAS_DUCKDB or not Path(self.duckdb_path).exists():
            return []
        conn = duckdb.connect(self.duckdb_path, read_only=True)
        try:
            return conn.execute(sql).fetchall()
        except Exception as e:
            print(f"[DuckDB] Query error: {e}")
            return []
        finally:
            conn.close()

    def _duck_query_df(self, sql: str):
        """DuckDB → dict list (pandas-free)"""
        if not HAS_DUCKDB or not Path(self.duckdb_path).exists():
            return []
        conn = duckdb.connect(self.duckdb_path, read_only=True)
        try:
            result = conn.execute(sql)
            columns = [desc[0] for desc in result.description]
            rows = result.fetchall()
            return [dict(zip(columns, row)) for row in rows]
        except Exception as e:
            print(f"[DuckDB] Query error: {e}")
            return []
        finally:
            conn.close()

    def _app_query(self, sql: str, params=()) -> list:
        """App backup SQLite 쿼리"""
        if not Path(self.app_db_path).exists():
            return []
        conn = sqlite3.connect(self.app_db_path)
        try:
            return conn.execute(sql, params).fetchall()
        except Exception as e:
            print(f"[AppDB] Query error: {e}")
            return []
        finally:
            conn.close()

    # ═══════════════════════════════════════
    # Model 1: 회복 상태 예측
    # ═══════════════════════════════════════

    def predict_recovery_state(self) -> dict:
        """오늘의 회복 상태 예측"""
        # 최근 수면 데이터 (3일)
        sleep_data = self._duck_query("""
            SELECT efficiency, score,
                   EXTRACT(EPOCH FROM (end_time - start_time))/3600.0 as hours
            FROM sleep_sessions
            ORDER BY start_time DESC LIMIT 3
        """)
        sleep_efficiency = 0.75  # default
        sleep_hours_avg = 7.0
        if sleep_data:
            effs = [r[0] for r in sleep_data if r[0] is not None]
            hours = [r[2] for r in sleep_data if r[2] is not None]
            if effs:
                sleep_efficiency = sum(effs) / len(effs) / 100.0
            if hours:
                sleep_hours_avg = sum(hours) / len(hours)

        # 최근 7일 훈련 부하 (거리 합 + 강도)
        training_data = self._duck_query("""
            SELECT distance_m, duration_min, avg_hr, max_hr
            FROM runs
            WHERE start_time_local >= CURRENT_DATE - INTERVAL '7 days'
            AND type_name NOT IN ('Walking', 'Cycling', 'Swimming')
            ORDER BY start_time_local DESC
        """)
        weekly_km = sum(r[0] for r in training_data if r[0]) / 1000.0 if training_data else 0
        weekly_sessions = len(training_data)
        avg_intensity = 0.5
        if training_data:
            hrs = [r[2] for r in training_data if r[2] is not None]
            if hrs:
                # 강도 = avg_hr / 추정 max_hr (190)
                avg_intensity = sum(hrs) / len(hrs) / 190.0

        # 안정 시 HR 추이 (최근 7일 최저 HR)
        hr_trend_data = self._duck_query("""
            SELECT DATE_TRUNC('day', timestamp) as d,
                   MIN(heart_rate) as min_hr
            FROM heart_rate_log
            WHERE heart_rate > 40 AND heart_rate < 100
            GROUP BY d
            ORDER BY d DESC LIMIT 7
        """)
        resting_hr = 60
        hr_trend = "STABLE"
        if hr_trend_data and len(hr_trend_data) >= 2:
            hrs = [r[1] for r in hr_trend_data if r[1] is not None]
            if hrs:
                resting_hr = int(sum(hrs) / len(hrs))
                if len(hrs) >= 4:
                    first_half = sum(hrs[:len(hrs)//2]) / (len(hrs)//2)
                    second_half = sum(hrs[len(hrs)//2:]) / (len(hrs) - len(hrs)//2)
                    if second_half - first_half > 3:
                        hr_trend = "RISING"  # HR 상승 = 피로
                    elif first_half - second_half > 3:
                        hr_trend = "FALLING"  # HR 하강 = 회복

        # 회복 점수 계산 (가중 평균)
        sleep_score = min(1.0, sleep_efficiency * 1.1) * 0.35  # 수면 효율 35%
        load_score = max(0, 1.0 - (weekly_km / 50.0) * avg_intensity) * 0.30  # 부하 역수 30%
        hr_score = max(0, 1.0 - (resting_hr - 50) / 30.0) * 0.35  # HR 안정성 35%
        recovery_score = round(min(1.0, max(0.0, sleep_score + load_score + hr_score)), 2)

        # 권장사항
        if recovery_score >= 0.8:
            recommendation = "hard"
            max_duration = 90
        elif recovery_score >= 0.6:
            recommendation = "moderate"
            max_duration = 60
        elif recovery_score >= 0.4:
            recommendation = "easy"
            max_duration = 40
        else:
            recommendation = "rest"
            max_duration = 0

        risk_factors = []
        if sleep_efficiency < 0.75:
            risk_factors.append(f"수면 효율 {sleep_efficiency*100:.0f}% (3일 평균)")
        if sleep_hours_avg < 6.5:
            risk_factors.append(f"수면 시간 부족 ({sleep_hours_avg:.1f}h 평균)")
        if hr_trend == "RISING":
            risk_factors.append("안정 시 HR 상승 추세 (피로 축적)")
        if weekly_km > 40:
            risk_factors.append(f"주간 러닝 {weekly_km:.0f}km (과부하 가능)")

        return {
            "recovery_score": recovery_score,
            "recommendation": recommendation,
            "optimal_hr_zone": "Z2" if recommendation in ("easy", "moderate") else "Z3" if recommendation == "hard" else "Z1",
            "max_suggested_duration_min": max_duration,
            "risk_factors": risk_factors,
            "resting_hr": resting_hr,
            "hr_trend": hr_trend,
            "sleep_efficiency_3d": round(sleep_efficiency, 2),
            "weekly_km": round(weekly_km, 1),
            "weekly_sessions": weekly_sessions,
            "confidence": 0.7 if sleep_data and hr_trend_data else 0.4
        }

    # ═══════════════════════════════════════
    # Model 2: 최적 페이스 예측
    # ═══════════════════════════════════════

    def get_critical_speed(self) -> dict:
        """Critical Speed + D' 계산 (physiological_engine.py 로직 포팅)"""
        if not HAS_NUMPY:
            return {"cs_mps": 3.0, "cs_kmh": 10.8, "pace_min_km": 5.56, "d_prime_m": 200, "source": "default"}

        rows = self._duck_query("""
            SELECT duration_min, distance_m, avg_hr, start_time_local
            FROM runs
            WHERE distance_m > 0 AND duration_min > 2
            AND (type_name IS NULL OR type_name IN ('Running', 'Hiking'))
            ORDER BY distance_m DESC
        """)
        if len(rows) < 2:
            return {"cs_mps": 3.0, "cs_kmh": 10.8, "pace_min_km": 5.56, "d_prime_m": 200, "source": "default"}

        # Best efforts per duration bin
        bins = [3, 5, 10, 12, 20, 30, 40, 60]
        best_efforts = []
        for b in bins:
            lower, upper = b * 0.9, b * 1.1
            subset = [r for r in rows if lower <= r[0] <= upper]
            if subset:
                best = max(subset, key=lambda r: r[1])
                speed = best[1] / (best[0] * 60)
                if 1.0 < speed < 10.0:
                    best_efforts.append({"duration_sec": best[0] * 60, "distance_m": best[1], "avg_hr": best[2]})

        if len(best_efforts) < 2:
            return {"cs_mps": 3.0, "cs_kmh": 10.8, "pace_min_km": 5.56, "d_prime_m": 200, "source": "default"}

        x = np.array([e["duration_sec"] for e in best_efforts])
        y = np.array([e["distance_m"] for e in best_efforts])
        cs, d_prime = np.polyfit(x, y, 1)

        cs_kmh = cs * 3.6
        pace_min_km = 60.0 / cs_kmh if cs_kmh > 0 else 0

        # LTHR
        threshold_efforts = [e for e in best_efforts if e["duration_sec"] > 600 and e["avg_hr"]]
        lthr = int(sum(e["avg_hr"] for e in threshold_efforts) / len(threshold_efforts)) if threshold_efforts else 165

        return {
            "cs_mps": round(cs, 3),
            "cs_kmh": round(cs_kmh, 1),
            "pace_min_km": round(pace_min_km, 2),
            "d_prime_m": round(d_prime, 0),
            "lthr_bpm": lthr,
            "best_efforts_count": len(best_efforts),
            "source": "calculated"
        }

    def predict_optimal_pace(self, target_distance_km: float = 5.0) -> dict:
        """목표 거리별 최적 페이스 예측"""
        cs_data = self.get_critical_speed()
        recovery = self.predict_recovery_state()

        cs_pace = cs_data["pace_min_km"]
        lthr = cs_data.get("lthr_bpm", 165)

        # 회복 상태에 따른 페이스 조정
        recovery_factor = 1.0
        if recovery["recovery_score"] < 0.4:
            recovery_factor = 1.20  # 20% 느리게
        elif recovery["recovery_score"] < 0.6:
            recovery_factor = 1.10  # 10% 느리게
        elif recovery["recovery_score"] < 0.8:
            recovery_factor = 1.05  # 5% 느리게

        # 거리에 따른 페이스 조정 (CS는 ~30분 지속 가능 속도)
        distance_factor = 1.0
        if target_distance_km <= 3:
            distance_factor = 0.92  # 짧으면 빠르게
        elif target_distance_km <= 5:
            distance_factor = 0.97
        elif target_distance_km <= 10:
            distance_factor = 1.05
        elif target_distance_km > 15:
            distance_factor = 1.15

        target_pace = round(cs_pace * recovery_factor * distance_factor, 2)

        # HR 목표
        if recovery["recommendation"] == "easy":
            hr_min, hr_max = int(lthr * 0.70), int(lthr * 0.80)
        elif recovery["recommendation"] == "moderate":
            hr_min, hr_max = int(lthr * 0.80), int(lthr * 0.90)
        else:
            hr_min, hr_max = int(lthr * 0.88), int(lthr * 0.95)

        warnings = []
        if recovery["hr_trend"] == "RISING":
            warnings.append("안정시 HR 상승 추세 — 무리하지 마세요")
        if recovery["recovery_score"] < 0.4:
            warnings.append("회복 부족 — 오늘은 휴식 권장")

        return {
            "target_distance_km": target_distance_km,
            "target_pace_min_km": target_pace,
            "hr_target": {"min": hr_min, "max": hr_max},
            "cadence_target": 170,
            "recovery_adjustment": f"{(recovery_factor - 1) * 100:+.0f}%",
            "warnings": warnings,
            "based_on": {
                "critical_speed": cs_data,
                "recovery_score": recovery["recovery_score"]
            }
        }

    # ═══════════════════════════════════════
    # Model 3: 수면 품질 예측
    # ═══════════════════════════════════════

    def predict_sleep_quality(self) -> dict:
        """오늘 밤 수면 품질 예측"""
        # 최근 14일 수면 패턴
        sleep_history = self._duck_query("""
            SELECT start_time, end_time, efficiency, score,
                   EXTRACT(HOUR FROM start_time) as bedtime_hour,
                   EXTRACT(EPOCH FROM (end_time - start_time))/3600.0 as hours
            FROM sleep_sessions
            ORDER BY start_time DESC LIMIT 14
        """)

        avg_efficiency = 0.78
        avg_bedtime_hour = 23.5
        avg_deep_pct = 0.16
        if sleep_history:
            effs = [r[2] for r in sleep_history if r[2] is not None]
            bedtimes = [r[4] for r in sleep_history if r[4] is not None]
            if effs:
                avg_efficiency = sum(effs) / len(effs) / 100.0
            if bedtimes:
                avg_bedtime_hour = sum(bedtimes) / len(bedtimes)

        # 수면 단계 분석
        stage_data = self._duck_query("""
            SELECT s.sleep_id, st.stage,
                   EXTRACT(EPOCH FROM (st.end_time - st.start_time))/60.0 as minutes
            FROM sleep_sessions s
            JOIN sleep_stages_ts st ON s.sleep_id = st.sleep_id
            WHERE s.start_time >= CURRENT_DATE - INTERVAL '14 days'
        """)
        if stage_data:
            total_min = sum(r[2] for r in stage_data if r[2])
            deep_min = sum(r[2] for r in stage_data if r[1] == 2 and r[2])  # stage 2 = Deep
            if total_min > 0:
                avg_deep_pct = round(deep_min / total_min, 2)

        # 오늘 활동량 (앱 데이터)
        today_activity = self._app_query("""
            SELECT value FROM structured_data
            WHERE domain = 'running_session'
            AND data_key LIKE ?
            ORDER BY updated_at DESC LIMIT 1
        """, (f"{datetime.now().strftime('%Y-%m-%d')}%",))

        had_exercise = len(today_activity) > 0
        exercise_boost = 0.05 if had_exercise else 0.0

        # 예측
        predicted_efficiency = round(min(1.0, avg_efficiency + exercise_boost), 2)
        optimal_bedtime_hour = int(avg_bedtime_hour)
        optimal_bedtime_min = int((avg_bedtime_hour - optimal_bedtime_hour) * 60)

        factors = []
        if had_exercise:
            factors.append("오늘 운동 → 수면 질 향상 예상")
        if avg_efficiency < 0.75:
            factors.append(f"최근 수면 효율 {avg_efficiency*100:.0f}% (개선 필요)")

        return {
            "predicted_efficiency": predicted_efficiency,
            "predicted_deep_sleep_pct": avg_deep_pct,
            "optimal_bedtime": f"{optimal_bedtime_hour:02d}:{optimal_bedtime_min:02d}",
            "avg_sleep_hours": round(sum(r[5] for r in sleep_history if r[5]) / max(1, len(sleep_history)), 1) if sleep_history else 7.0,
            "factors": factors,
            "confidence": 0.65 if len(sleep_history) >= 7 else 0.4
        }

    # ═══════════════════════════════════════
    # Model 4: 부상 위험 예측
    # ═══════════════════════════════════════

    def predict_injury_risk(self) -> dict:
        """ACWR + 러닝 역학 편차 기반 부상 위험 예측"""
        # Acute load (최근 7일) vs Chronic load (최근 28일)
        acute_data = self._duck_query("""
            SELECT SUM(distance_m) / 1000.0 as km, COUNT(*) as sessions
            FROM runs
            WHERE start_time_local >= CURRENT_DATE - INTERVAL '7 days'
            AND (type_name IS NULL OR type_name = 'Running')
        """)
        chronic_data = self._duck_query("""
            SELECT SUM(distance_m) / 1000.0 / 4.0 as weekly_avg_km
            FROM runs
            WHERE start_time_local >= CURRENT_DATE - INTERVAL '28 days'
            AND (type_name IS NULL OR type_name = 'Running')
        """)

        acute_km = acute_data[0][0] if acute_data and acute_data[0][0] else 0
        chronic_km = chronic_data[0][0] if chronic_data and chronic_data[0][0] else 1

        acwr = round(acute_km / max(0.1, chronic_km), 2)

        # 역학 편차 (최근 5회 vs 이전 20회 평균)
        mechanics_recent = self._duck_query("""
            SELECT avg_gct_ms, avg_vertical_oscillation_cm, avg_stiffness_kn_m, asymmetry_score
            FROM running_dynamics
            ORDER BY start_time DESC LIMIT 5
        """)
        mechanics_baseline = self._duck_query("""
            SELECT AVG(avg_gct_ms), AVG(avg_vertical_oscillation_cm),
                   AVG(avg_stiffness_kn_m), AVG(asymmetry_score)
            FROM (
                SELECT avg_gct_ms, avg_vertical_oscillation_cm,
                       avg_stiffness_kn_m, asymmetry_score
                FROM running_dynamics
                ORDER BY start_time DESC LIMIT 20
            )
        """)

        flags = []
        gct_deviation = 0
        asymmetry_alert = False

        if mechanics_recent and mechanics_baseline and mechanics_baseline[0][0]:
            baseline_gct = mechanics_baseline[0][0]
            recent_gcts = [r[0] for r in mechanics_recent if r[0] is not None]
            if recent_gcts and baseline_gct:
                recent_avg_gct = sum(recent_gcts) / len(recent_gcts)
                gct_deviation = (recent_avg_gct - baseline_gct) / baseline_gct * 100
                if gct_deviation > 5:
                    flags.append(f"GCT 상승 {gct_deviation:.0f}% (피로/부상 징후)")

            baseline_asym = mechanics_baseline[0][3]
            recent_asyms = [r[3] for r in mechanics_recent if r[3] is not None]
            if recent_asyms and baseline_asym is not None:
                recent_avg_asym = sum(recent_asyms) / len(recent_asyms)
                if recent_avg_asym > baseline_asym * 1.3:
                    asymmetry_alert = True
                    flags.append("좌우 비대칭 증가 (보상 동작 가능성)")

        # ACWR 판정
        if acwr > 1.5:
            flags.append(f"ACWR {acwr} (급격한 훈련량 증가)")
        elif acwr < 0.8 and acute_km > 0:
            flags.append(f"ACWR {acwr} (디트레이닝 상태)")

        # 종합 판정
        risk_score = 0
        if acwr > 1.5:
            risk_score += 2
        elif acwr > 1.3:
            risk_score += 1
        if gct_deviation > 5:
            risk_score += 1
        if asymmetry_alert:
            risk_score += 1

        risk_level = "low" if risk_score <= 1 else "moderate" if risk_score <= 2 else "high"

        return {
            "risk_level": risk_level,
            "acwr": acwr,
            "acute_km_7d": round(acute_km, 1),
            "chronic_weekly_avg_km": round(chronic_km, 1),
            "gct_deviation_pct": round(gct_deviation, 1),
            "flags": flags,
            "suggestion": {
                "low": "현재 훈련량 적절",
                "moderate": "훈련 강도 유지, 역학 지표 모니터링 권장",
                "high": "훈련량 감소 + 회복일 추가 권장"
            }.get(risk_level, ""),
            "confidence": 0.7 if mechanics_recent else 0.4
        }

    # ═══════════════════════════════════════
    # Model 5: 주간 프로파일 (앱 되먹임 핵심)
    # ═══════════════════════════════════════

    def get_running_signature(self) -> dict:
        """10년 러닝 역학 시그니처 요약"""
        data = self._duck_query("""
            SELECT AVG(avg_stiffness_kn_m), AVG(avg_gct_ms),
                   AVG(avg_vertical_oscillation_cm), AVG(avg_flight_time_ms),
                   AVG(regularity_score), AVG(asymmetry_score),
                   COUNT(*)
            FROM running_dynamics
        """)
        if not data or not data[0][0]:
            return {"type": "unknown", "sample_count": 0}

        r = data[0]
        stiffness = r[0]
        gct = r[1]

        # 러너 타입 분류
        if stiffness and stiffness > 9.0:
            runner_type = "elastic"  # 탄성 의존형
        elif gct and gct > 250:
            runner_type = "grinder"  # 접지 의존형
        else:
            runner_type = "balanced"

        return {
            "type": runner_type,
            "avg_stiffness_kn": round(r[0], 1) if r[0] else None,
            "avg_gct_ms": round(r[1], 0) if r[1] else None,
            "avg_vertical_osc_cm": round(r[2], 1) if r[2] else None,
            "avg_flight_time_ms": round(r[3], 0) if r[3] else None,
            "regularity_score": round(r[4], 2) if r[4] else None,
            "asymmetry_score": round(r[5], 2) if r[5] else None,
            "sample_count": r[6] or 0
        }

    def get_training_zones(self, lthr: int = 165) -> dict:
        """LTHR 기반 5-zone 훈련 구역"""
        return {
            "z1_recovery":  {"hr_min": 0, "hr_max": int(lthr * 0.80), "description": "Recovery"},
            "z2_aerobic":   {"hr_min": int(lthr * 0.81), "hr_max": int(lthr * 0.89), "description": "Aerobic"},
            "z3_tempo":     {"hr_min": int(lthr * 0.90), "hr_max": int(lthr * 0.94), "description": "Tempo"},
            "z4_threshold": {"hr_min": int(lthr * 0.95), "hr_max": int(lthr * 1.00), "description": "Threshold"},
            "z5_vo2max":    {"hr_min": int(lthr * 1.01), "hr_max": int(lthr * 1.15), "description": "VO2max"},
        }

    def get_sleep_baseline(self) -> dict:
        """장기 수면 기저선"""
        data = self._duck_query("""
            SELECT AVG(efficiency), COUNT(*),
                   AVG(EXTRACT(EPOCH FROM (end_time - start_time))/3600.0)
            FROM sleep_sessions
            WHERE efficiency IS NOT NULL
        """)
        if not data or not data[0][0]:
            return {"avg_efficiency": 0.78, "total_sessions": 0, "avg_hours": 7.0}

        return {
            "avg_efficiency": round(data[0][0] / 100.0, 2),
            "total_sessions": data[0][1] or 0,
            "avg_hours": round(data[0][2], 1) if data[0][2] else 7.0
        }

    def get_resting_hr_baseline(self) -> dict:
        """안정시 HR 장기 기저선"""
        data = self._duck_query("""
            SELECT DATE_TRUNC('month', timestamp) as m,
                   MIN(heart_rate) as min_hr,
                   AVG(heart_rate) as avg_hr
            FROM heart_rate_log
            WHERE heart_rate > 40 AND heart_rate < 100
            GROUP BY m
            ORDER BY m DESC LIMIT 12
        """)
        if not data:
            return {"resting_hr": 60, "months_tracked": 0}

        min_hrs = [r[1] for r in data if r[1] is not None]
        return {
            "resting_hr": int(sum(min_hrs) / len(min_hrs)) if min_hrs else 60,
            "monthly_trend": [{"month": str(r[0])[:7], "min_hr": r[1], "avg_hr": round(r[2], 0) if r[2] else None} for r in data[:6]],
            "months_tracked": len(data)
        }

    def get_activity_summary(self) -> dict:
        """총 활동 요약 (10년)"""
        data = self._duck_query("""
            SELECT type_name, COUNT(*), SUM(distance_m)/1000.0, SUM(duration_min)/60.0
            FROM runs
            GROUP BY type_name
        """)
        summary = {}
        total_km = 0
        total_hours = 0
        for r in data:
            name = r[0] or "Unknown"
            km = round(r[2], 1) if r[2] else 0
            hours = round(r[3], 1) if r[3] else 0
            summary[name] = {"count": r[1], "km": km, "hours": hours}
            total_km += km
            total_hours += hours

        return {
            "by_type": summary,
            "total_km": round(total_km, 0),
            "total_hours": round(total_hours, 0),
            "total_sessions": sum(v["count"] for v in summary.values())
        }

    def build_weekly_profile(self) -> dict:
        """주간 디지털트윈 프로파일 — 앱에 되먹이는 핵심 JSON"""
        cs_data = self.get_critical_speed()
        lthr = cs_data.get("lthr_bpm", 165)
        running_sig = self.get_running_signature()
        sleep_bl = self.get_sleep_baseline()
        hr_bl = self.get_resting_hr_baseline()
        activity = self.get_activity_summary()

        return {
            "timestamp": int(time.time() * 1000),
            "version": "weekly_v1",
            "generated_at": datetime.now().isoformat(),

            # 생리적 기저선 (장기 데이터 기반)
            "baselines": {
                "resting_hr": hr_bl["resting_hr"],
                "critical_speed_mps": cs_data["cs_mps"],
                "critical_speed_pace": cs_data["pace_min_km"],
                "lthr_bpm": lthr,
                "d_prime_m": cs_data["d_prime_m"],
                "avg_sleep_efficiency": sleep_bl["avg_efficiency"],
                "avg_sleep_hours": sleep_bl["avg_hours"],
                "hr_monthly_trend": hr_bl.get("monthly_trend", [])
            },

            # 러닝 역학 시그니처
            "running_signature": running_sig,

            # 훈련 구역 (5-zone)
            "training_zones": self.get_training_zones(lthr),

            # 활동 총계
            "activity_summary": activity,

            # 일일 예측 (매일 갱신)
            "daily_predictions": {
                "recovery": self.predict_recovery_state(),
                "injury_risk": self.predict_injury_risk(),
                "sleep": self.predict_sleep_quality(),
                "optimal_pace_5k": self.predict_optimal_pace(5.0),
                "optimal_pace_10k": self.predict_optimal_pace(10.0)
            }
        }

    def build_daily_predictions(self) -> dict:
        """일일 예측 전용 (경량 — 매일 아침 pull)"""
        return {
            "timestamp": int(time.time() * 1000),
            "generated_at": datetime.now().isoformat(),
            "recovery": self.predict_recovery_state(),
            "injury_risk": self.predict_injury_risk(),
            "sleep": self.predict_sleep_quality(),
            "optimal_pace_5k": self.predict_optimal_pace(5.0)
        }


# ── CLI 테스트 ──

if __name__ == "__main__":
    print("=== Digital Twin Prediction Engine ===\n")
    predictor = DigitalTwinPredictor()

    print("--- Critical Speed ---")
    cs = predictor.get_critical_speed()
    print(json.dumps(cs, indent=2, ensure_ascii=False))

    print("\n--- Recovery State ---")
    recovery = predictor.predict_recovery_state()
    print(json.dumps(recovery, indent=2, ensure_ascii=False))

    print("\n--- Optimal Pace (5K) ---")
    pace = predictor.predict_optimal_pace(5.0)
    print(json.dumps(pace, indent=2, ensure_ascii=False))

    print("\n--- Injury Risk ---")
    injury = predictor.predict_injury_risk()
    print(json.dumps(injury, indent=2, ensure_ascii=False))

    print("\n--- Sleep Quality ---")
    sleep = predictor.predict_sleep_quality()
    print(json.dumps(sleep, indent=2, ensure_ascii=False))

    print("\n--- Weekly Profile ---")
    profile = predictor.build_weekly_profile()
    print(json.dumps(profile, indent=2, ensure_ascii=False))
