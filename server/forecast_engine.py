#!/usr/bin/env python3
"""
XREAL Forecast Engine — TFT + NeuralProphet 행동/주기 예측

Phase 2 예측 레이어:
  - TFT (Temporal Fusion Transformer): 30분~2시간 행동 예측
  - NeuralProphet: 일/주 주기 패턴 탐지 + 예측

입력: episode_store (L2), backup.db, DuckDB (장기 이력)
출력: data/forecasts/ + Orchestrator 엔드포인트 + Mem0 인사이트

Usage:
    pip install neuralprophet pytorch-forecasting
    python forecast_engine.py              # 즉시 학습+예측
    python forecast_engine.py --predict    # 예측만 (학습된 모델 사용)
    python forecast_engine.py --schedule   # 매일 새벽 5시 자동
"""

import os
import json
import time
import sqlite3
import logging
import argparse
from datetime import datetime, timedelta
from pathlib import Path
from collections import Counter

import numpy as np
import pandas as pd

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("logs/forecast_engine.log", encoding="utf-8"),
    ],
)
log = logging.getLogger("forecast_engine")

# ─── Config ───

EPISODE_DB_PATH = os.getenv("EPISODE_DB", "./data/episodes.db")
BACKUP_DB_PATH = os.getenv("BACKUP_DB", "./backup.db")
MODEL_DIR = Path(os.getenv("FORECAST_MODEL_DIR", "./data/models"))
FORECAST_DIR = Path(os.getenv("FORECAST_DIR", "./data/forecasts"))

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
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    FORECAST_DIR.mkdir(parents=True, exist_ok=True)
    Path("logs").mkdir(exist_ok=True)


# ═════════════════════════════════════════════
# 1. 데이터 준비
# ═════════════════════════════════════════════

def load_episode_timeseries(days: int = 14) -> pd.DataFrame:
    """에피소드 DB에서 시간대별 활동 시계열 생성"""
    db_path = EPISODE_DB_PATH
    if not Path(db_path).exists():
        log.warning(f"에피소드 DB 없음: {db_path}")
        return pd.DataFrame()

    conn = sqlite3.connect(db_path)
    try:
        cutoff = time.time() - days * 86400
        rows = conn.execute("""
            SELECT event_type, content, timestamp, metadata
            FROM episodes WHERE timestamp >= ?
            ORDER BY timestamp ASC
        """, (cutoff,)).fetchall()
    finally:
        conn.close()

    if not rows:
        log.info("에피소드 데이터 없음")
        return pd.DataFrame()

    # 시간당 집계
    records = []
    for etype, content, ts, meta in rows:
        dt = datetime.fromtimestamp(ts)
        hour_key = dt.replace(minute=0, second=0, microsecond=0)

        # 활동 강도 계산
        intensity = {"vision": 0.3, "speech": 0.5, "sensor": 0.2,
                     "interaction": 0.8, "reflection": 0.1}.get(etype, 0.3)

        # 감정 점수
        emotion = "neutral"
        if meta:
            try:
                m = json.loads(meta)
                emotion = m.get("emotion", "neutral")
            except (json.JSONDecodeError, TypeError):
                pass

        emotion_score = {"positive": 1, "neutral": 0, "negative": -1, "mixed": 0}.get(emotion, 0)

        records.append({
            "ds": hour_key,
            "activity": intensity,
            "emotion": emotion_score,
            "event_type": etype,
        })

    if not records:
        return pd.DataFrame()

    df = pd.DataFrame(records)

    # 시간당 집계
    hourly = df.groupby("ds").agg(
        y=("activity", "sum"),           # 활동 강도 합계
        emotion=("emotion", "mean"),     # 감정 평균
        event_count=("activity", "count"),  # 이벤트 수
    ).reset_index()

    # 빈 시간 채우기
    if len(hourly) >= 2:
        full_range = pd.date_range(hourly["ds"].min(), hourly["ds"].max(), freq="h")
        hourly = hourly.set_index("ds").reindex(full_range).fillna(0).reset_index()
        hourly.rename(columns={"index": "ds"}, inplace=True)

    return hourly


def generate_synthetic_data(days: int = 14) -> pd.DataFrame:
    """더미 시계열 생성 (데이터 부족 시 학습/테스트용)"""
    log.info(f"합성 데이터 생성: {days}일")
    hours = days * 24
    dates = pd.date_range(end=datetime.now().replace(minute=0, second=0, microsecond=0),
                          periods=hours, freq="h")

    np.random.seed(42)
    records = []
    for dt in dates:
        h = dt.hour
        dow = dt.weekday()

        # 기본 활동 패턴 (교사 일과)
        if dow < 5:  # 평일
            if 7 <= h < 8:
                activity = 1.5 + np.random.normal(0, 0.3)  # 출근
            elif 9 <= h < 12:
                activity = 3.0 + np.random.normal(0, 0.5)  # 오전 수업
            elif 12 <= h < 13:
                activity = 1.0 + np.random.normal(0, 0.2)  # 점심
            elif 13 <= h < 16:
                activity = 2.5 + np.random.normal(0, 0.5)  # 오후 수업
            elif 16 <= h < 17:
                activity = 1.5 + np.random.normal(0, 0.3)  # 방과 후
            elif 17 <= h < 18:
                activity = 2.0 + np.random.normal(0, 0.4)  # 운동
            elif 6 <= h < 7:
                activity = 0.8 + np.random.normal(0, 0.2)  # 아침
            else:
                activity = 0.3 + np.random.normal(0, 0.1)  # 휴식/수면
        else:  # 주말
            if 9 <= h < 11:
                activity = 1.0 + np.random.normal(0, 0.3)
            elif 14 <= h < 17:
                activity = 1.5 + np.random.normal(0, 0.4)
            else:
                activity = 0.3 + np.random.normal(0, 0.1)

        activity = max(0, activity)

        # 감정 패턴
        if 9 <= h < 12 and dow < 5:
            emotion = 0.3 + np.random.normal(0, 0.3)  # 수업 = 약간 긍정
        elif 16 <= h < 17 and dow < 5:
            emotion = -0.2 + np.random.normal(0, 0.3)  # 퇴근 전 피로
        else:
            emotion = np.random.normal(0, 0.2)

        emotion = np.clip(emotion, -1, 1)

        records.append({
            "ds": dt,
            "y": round(activity, 2),
            "emotion": round(emotion, 2),
            "event_count": max(0, int(activity * 3 + np.random.normal(0, 1))),
        })

    return pd.DataFrame(records)


# ═════════════════════════════════════════════
# 2. NeuralProphet — 주기 패턴 탐지 + 예측
# ═════════════════════════════════════════════

def train_neuralprophet(df: pd.DataFrame) -> dict:
    """NeuralProphet으로 주기 패턴 학습 + 24시간 예측"""
    from neuralprophet import NeuralProphet

    log.info(f"NeuralProphet 학습: {len(df)}행")

    # 모델 생성
    model = NeuralProphet(
        growth="off",
        n_changepoints=0,
        yearly_seasonality=False,
        weekly_seasonality=True,      # 주간 패턴
        daily_seasonality=True,       # 일간 패턴
        n_forecasts=24,               # 24시간 예측
        n_lags=48,                    # 48시간 과거 참조
        learning_rate=0.01,
        epochs=50,
        batch_size=32,
    )

    # 추가 회귀자 (감정)
    if "emotion" in df.columns:
        model = model.add_lagged_regressor("emotion", n_lags=24)

    # 학습
    train_df = df[["ds", "y"]].copy()
    if "emotion" in df.columns:
        train_df["emotion"] = df["emotion"].values

    metrics = model.fit(train_df, freq="h")
    # 버전에 따라 MAE_val 또는 MAE 키 사용
    mae_key = "MAE_val" if "MAE_val" in metrics.columns else "MAE"
    mae_val = float(metrics[mae_key].iloc[-1])
    log.info(f"NeuralProphet 학습 완료: MAE={mae_val:.3f}")

    # 예측
    future = model.make_future_dataframe(train_df, periods=24)
    if "emotion" in df.columns:
        future["emotion"] = 0.0  # 미래 감정은 중립 가정
    forecast = model.predict(future)

    # 주기성 분해 — 원본 데이터의 시간대/요일별 평균 (NeuralProphet predict는 sparse하므로)
    components = {}
    try:
        # 원본 데이터에서 패턴 추출 (학습 데이터가 가장 정확한 패턴 소스)
        src = df.copy()
        src["hour"] = src["ds"].dt.hour
        src["dow"] = src["ds"].dt.weekday

        # 일간 패턴
        daily = []
        hourly_mean = src.groupby("hour")["y"].mean()
        for h, val in hourly_mean.items():
            daily.append({"hour": int(h), "trend": round(float(val), 2)})
        components["daily_pattern"] = daily

        # 주간 패턴
        weekly = []
        dow_names = ["월", "화", "수", "목", "금", "토", "일"]
        dow_mean = src.groupby("dow")["y"].mean()
        for dow, val in dow_mean.items():
            weekly.append({"day": dow_names[int(dow)], "trend": round(float(val), 2)})
        components["weekly_pattern"] = weekly
    except Exception as e:
        log.warning(f"패턴 분해 실패: {e}")

    # 모델 저장 (torch checkpoint)
    model_path = MODEL_DIR / "neuralprophet_activity.pt"
    try:
        import torch
        torch.save(model.model.state_dict(), str(model_path))
        log.info(f"모델 저장: {model_path}")
    except Exception as e:
        log.warning(f"모델 저장 실패: {e}")

    # 예측 결과
    forecast_24h = []
    future_mask = forecast["ds"] > df["ds"].max()
    for _, row in forecast[future_mask].iterrows():
        forecast_24h.append({
            "timestamp": row["ds"].isoformat(),
            "hour": row["ds"].hour,
            "predicted_activity": round(float(row["yhat1"]), 2),
        })

    return {
        "model": "NeuralProphet",
        "train_size": len(df),
        "mae": mae_val,
        "forecast_24h": forecast_24h[:24],
        "components": components,
    }


# ═════════════════════════════════════════════
# 3. 간이 TFT — 행동 예측 (PyTorch Forecasting)
# ═════════════════════════════════════════════

def train_simple_tft(df: pd.DataFrame) -> dict:
    """
    간이 TFT 기반 다변량 시계열 예측.
    pytorch-forecasting TFT는 대규모 데이터에 적합하므로,
    데이터 부족 시 간이 LSTM으로 대체.
    """
    log.info(f"행동 예측 모델 학습: {len(df)}행")

    # 데이터가 충분하면 pytorch-forecasting TFT, 아니면 간이 방식
    if len(df) < 168:  # 1주일 미만
        return _simple_forecast(df)

    try:
        return _tft_forecast(df)
    except Exception as e:
        log.warning(f"TFT 학습 실패, 간이 방식 폴백: {e}")
        return _simple_forecast(df)


def _simple_forecast(df: pd.DataFrame) -> dict:
    """간이 예측 (시간대별 평균 기반)"""
    log.info("간이 예측 (시간대별 평균)")

    df["hour"] = df["ds"].dt.hour
    df["dow"] = df["ds"].dt.weekday

    # 시간대×요일별 평균
    profile = df.groupby(["dow", "hour"]).agg(
        mean_activity=("y", "mean"),
        std_activity=("y", "std"),
        mean_emotion=("emotion", "mean"),
    ).reset_index()

    # 내일 24시간 예측
    tomorrow = datetime.now() + timedelta(days=1)
    tomorrow_dow = tomorrow.weekday()

    forecast_24h = []
    for h in range(24):
        mask = (profile["dow"] == tomorrow_dow) & (profile["hour"] == h)
        if mask.any():
            row = profile[mask].iloc[0]
            pred = float(row["mean_activity"])
            std = float(row["std_activity"]) if not np.isnan(row["std_activity"]) else 0.5
        else:
            pred = 0.5
            std = 0.5

        forecast_24h.append({
            "timestamp": tomorrow.replace(hour=h, minute=0, second=0).isoformat(),
            "hour": h,
            "predicted_activity": round(pred, 2),
            "confidence_low": round(max(0, pred - std), 2),
            "confidence_high": round(pred + std, 2),
        })

    return {
        "model": "HourlyMean",
        "train_size": len(df),
        "forecast_24h": forecast_24h,
        "hourly_profile": profile.to_dict("records") if len(profile) <= 168 else [],
    }


def _tft_forecast(df: pd.DataFrame) -> dict:
    """PyTorch Forecasting TFT (데이터 충분 시)"""
    from pytorch_forecasting import TimeSeriesDataSet, TemporalFusionTransformer
    import lightning.pytorch as pl

    log.info("TFT 학습 시작")

    # 데이터 준비
    df = df.copy()
    df["time_idx"] = range(len(df))
    df["group"] = "main"
    df["hour"] = df["ds"].dt.hour.astype(float)
    df["dow"] = df["ds"].dt.weekday.astype(float)

    max_encoder_length = 48
    max_prediction_length = 24
    training_cutoff = df["time_idx"].max() - max_prediction_length

    training = TimeSeriesDataSet(
        df[df["time_idx"] <= training_cutoff],
        time_idx="time_idx",
        target="y",
        group_ids=["group"],
        max_encoder_length=max_encoder_length,
        max_prediction_length=max_prediction_length,
        time_varying_known_reals=["time_idx", "hour", "dow"],
        time_varying_unknown_reals=["y", "emotion"],
        target_normalizer=None,
    )

    validation = TimeSeriesDataSet.from_dataset(
        training, df, min_prediction_idx=training_cutoff + 1
    )

    train_dl = training.to_dataloader(batch_size=32, shuffle=True)
    val_dl = validation.to_dataloader(batch_size=32, shuffle=False)

    # 모델
    tft = TemporalFusionTransformer.from_dataset(
        training,
        hidden_size=16,
        attention_head_size=1,
        dropout=0.1,
        hidden_continuous_size=8,
        learning_rate=0.03,
        reduce_on_plateau_patience=4,
    )

    trainer = pl.Trainer(
        max_epochs=30,
        accelerator="auto",
        enable_progress_bar=False,
        logger=False,
    )
    trainer.fit(tft, train_dataloaders=train_dl, val_dataloaders=val_dl)

    # 예측
    predictions = tft.predict(val_dl)
    pred_values = predictions.cpu().numpy().flatten()

    forecast_24h = []
    last_ts = df["ds"].max()
    for i, val in enumerate(pred_values[:24]):
        ts = last_ts + timedelta(hours=i + 1)
        forecast_24h.append({
            "timestamp": ts.isoformat(),
            "hour": ts.hour,
            "predicted_activity": round(float(val), 2),
        })

    # 모델 저장
    model_path = MODEL_DIR / "tft_activity.ckpt"
    trainer.save_checkpoint(str(model_path))

    return {
        "model": "TFT",
        "train_size": len(df),
        "forecast_24h": forecast_24h,
    }


# ═════════════════════════════════════════════
# 4. 통합 인사이트 생성
# ═════════════════════════════════════════════

def generate_forecast_insights(np_result: dict, tft_result: dict) -> list[str]:
    """예측 결과에서 인사이트 추출"""
    insights = []

    # NeuralProphet 주기 패턴
    components = np_result.get("components", {})
    daily = components.get("daily_pattern", [])
    if daily:
        peak_hour = max(daily, key=lambda x: x["trend"])
        low_hour = min(daily, key=lambda x: x["trend"])
        insights.append(
            f"[일간 패턴] 가장 활동적인 시간: {peak_hour['hour']}시 "
            f"(강도={peak_hour['trend']:.1f}), "
            f"가장 조용한 시간: {low_hour['hour']}시"
        )

    weekly = components.get("weekly_pattern", [])
    if weekly:
        peak_day = max(weekly, key=lambda x: x["trend"])
        low_day = min(weekly, key=lambda x: x["trend"])
        insights.append(
            f"[주간 패턴] 가장 바쁜 요일: {peak_day['day']} "
            f"(강도={peak_day['trend']:.1f}), "
            f"가장 여유로운 요일: {low_day['day']}"
        )

    # 내일 예측 요약
    forecast = tft_result.get("forecast_24h", [])
    if forecast:
        busy_hours = [f for f in forecast if f["predicted_activity"] > 2.0]
        if busy_hours:
            hours_str = ", ".join(f"{f['hour']}시" for f in busy_hours[:5])
            insights.append(f"[내일 예측] 바쁠 시간대: {hours_str}")

        quiet_hours = [f for f in forecast if 6 <= f["hour"] <= 22
                      and f["predicted_activity"] < 0.5]
        if quiet_hours:
            hours_str = ", ".join(f"{f['hour']}시" for f in quiet_hours[:3])
            insights.append(f"[내일 예측] 여유 시간대: {hours_str}")

    return insights


# ═════════════════════════════════════════════
# 5. 메인
# ═════════════════════════════════════════════

def run_forecast(predict_only: bool = False):
    """메인 예측 엔진"""
    log.info("=" * 60)
    log.info(f"예측 엔진 시작: {datetime.now().isoformat()}")
    log.info("=" * 60)

    ensure_dirs()

    # 1. 데이터 로드
    df = load_episode_timeseries(days=14)
    if len(df) < 48:
        log.warning(f"데이터 부족 ({len(df)}행), 합성 데이터 사용")
        df = generate_synthetic_data(days=14)

    log.info(f"데이터: {len(df)}행, 범위: {df['ds'].min()} ~ {df['ds'].max()}")

    # 2. NeuralProphet
    try:
        np_result = train_neuralprophet(df)
        log.info(f"NeuralProphet: MAE={np_result.get('mae', 'N/A')}")
    except Exception as e:
        log.error(f"NeuralProphet 실패: {e}")
        np_result = {"model": "NeuralProphet", "error": str(e)}

    # 3. TFT / 간이 예측
    try:
        tft_result = train_simple_tft(df)
        log.info(f"행동 예측: {tft_result['model']}")
    except Exception as e:
        log.error(f"행동 예측 실패: {e}")
        tft_result = {"model": "error", "error": str(e)}

    # 4. 인사이트
    insights = generate_forecast_insights(np_result, tft_result)
    for ins in insights:
        log.info(f"  {ins}")

    # 5. 리포트 저장
    report = {
        "date": datetime.now().strftime("%Y-%m-%d"),
        "generated_at": datetime.now().isoformat(),
        "neuralprophet": {k: v for k, v in np_result.items()
                         if k != "hourly_profile"},
        "activity_forecast": tft_result,
        "insights": insights,
    }

    report_path = FORECAST_DIR / f"forecast_{report['date']}.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str),
                          encoding="utf-8")
    log.info(f"리포트 저장: {report_path}")

    # 6. Mem0에 인사이트 저장
    try:
        import httpx
        orchestrator_url = os.getenv("ORCHESTRATOR_URL", "http://127.0.0.1:8091")
        with httpx.Client(timeout=10) as client:
            for insight in insights:
                client.post(
                    f"{orchestrator_url}/v1/mem0/add",
                    json={
                        "content": f"[행동 예측] {insight}",
                        "user_id": "teacher",
                        "event_type": "reflection",
                    },
                )
    except Exception as e:
        log.warning(f"Mem0 저장 실패: {e}")

    log.info("=" * 60)
    log.info(f"예측 완료: {len(insights)}개 인사이트")
    log.info("=" * 60)

    return report


def schedule_daily():
    """매일 새벽 5시 실행"""
    import sched
    import time as time_module

    scheduler = sched.scheduler(time_module.time, time_module.sleep)

    def next_5am() -> float:
        now = datetime.now()
        target = now.replace(hour=5, minute=0, second=0, microsecond=0)
        if now >= target:
            target += timedelta(days=1)
        return (target - now).total_seconds()

    def scheduled_run():
        try:
            run_forecast()
        except Exception as e:
            log.error(f"예측 에러: {e}", exc_info=True)
        delay = next_5am()
        scheduler.enter(delay, 1, scheduled_run)

    delay = next_5am()
    log.info(f"예측 스케줄러 시작. 첫 실행: {delay / 3600:.1f}시간 후")
    scheduler.enter(delay, 1, scheduled_run)
    scheduler.run()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="XREAL Forecast Engine")
    parser.add_argument("--predict", action="store_true", help="예측만 (학습 스킵)")
    parser.add_argument("--schedule", action="store_true", help="매일 새벽 5시 스케줄")
    args = parser.parse_args()

    if args.schedule:
        schedule_daily()
    else:
        run_forecast(predict_only=args.predict)
