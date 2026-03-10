#!/usr/bin/env python3
"""
XREAL 통합 배치 스케줄러 — 모든 야간/주간/월간 배치를 순서대로 실행

스케줄:
  03:00  야간 반성 (nightly_reflection.py)
  04:00  토픽 마이닝 (topic_miner.py) — 일요일만
  04:30  시계열 마이닝 (timeseries_miner.py)
  05:00  행동 예측 (forecast_engine.py)
  05:00  지식 그래프 (knowledge_graph.py) — 매월 1일만

Usage:
    python batch_scheduler.py              # 즉시 전체 실행 (테스트용)
    python batch_scheduler.py --schedule   # 스케줄 대기
    python batch_scheduler.py --dry-run    # 순서만 표시
"""

import os
import sys
import time
import logging
import argparse
import subprocess
from datetime import datetime, timedelta
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [batch] %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler("logs/batch_scheduler.log", encoding="utf-8"),
    ],
)
log = logging.getLogger("batch")

Path("logs").mkdir(exist_ok=True)

PYTHON = sys.executable
SCRIPT_DIR = Path(__file__).parent


def run_script(name: str, script: str, timeout_sec: int = 600) -> dict:
    """스크립트 실행 후 결과 반환"""
    script_path = SCRIPT_DIR / script
    if not script_path.exists():
        log.error(f"[{name}] 스크립트 없음: {script_path}")
        return {"name": name, "status": "missing", "script": script}

    log.info(f"[{name}] 시작: {script}")
    start = time.time()

    try:
        result = subprocess.run(
            [PYTHON, str(script_path)],
            capture_output=True,
            text=True,
            timeout=timeout_sec,
            encoding="utf-8",
            errors="replace",
            cwd=str(SCRIPT_DIR),
        )
        elapsed = time.time() - start

        if result.returncode == 0:
            log.info(f"[{name}] 완료 ({elapsed:.1f}s)")
            return {
                "name": name,
                "status": "success",
                "elapsed_sec": round(elapsed, 1),
                "stdout_tail": result.stdout[-500:] if result.stdout else "",
            }
        else:
            log.error(f"[{name}] 실패 (code={result.returncode}, {elapsed:.1f}s)")
            return {
                "name": name,
                "status": "failed",
                "return_code": result.returncode,
                "elapsed_sec": round(elapsed, 1),
                "stderr_tail": result.stderr[-500:] if result.stderr else "",
            }
    except subprocess.TimeoutExpired:
        log.error(f"[{name}] 타임아웃 ({timeout_sec}s)")
        return {"name": name, "status": "timeout", "timeout_sec": timeout_sec}
    except Exception as e:
        log.error(f"[{name}] 에러: {e}")
        return {"name": name, "status": "error", "error": str(e)}


def run_all_batches(now: datetime = None):
    """조건에 따라 배치 실행"""
    if now is None:
        now = datetime.now()

    dow = now.weekday()  # 0=월 6=일
    dom = now.day        # 1~31

    log.info("=" * 60)
    log.info(f"배치 스케줄러 시작: {now.strftime('%Y-%m-%d %H:%M')} "
             f"(요일={dow}, 일={dom})")
    log.info("=" * 60)

    results = []

    # 1. 야간 반성 (매일)
    results.append(run_script("reflection", "nightly_reflection.py", timeout_sec=300))

    # 2. 토픽 마이닝 (일요일만)
    if dow == 6:
        results.append(run_script("topic", "topic_miner.py", timeout_sec=600))
    else:
        log.info("[topic] 스킵 (일요일만 실행)")
        results.append({"name": "topic", "status": "skipped", "reason": "not_sunday"})

    # 3. 시계열 마이닝 (매일)
    results.append(run_script("timeseries", "timeseries_miner.py", timeout_sec=300))

    # 4. 행동 예측 (매일)
    results.append(run_script("forecast", "forecast_engine.py", timeout_sec=600))

    # 5. 지식 그래프 (매월 1일)
    if dom == 1:
        results.append(run_script("knowledge_graph", "knowledge_graph.py", timeout_sec=900))
    else:
        log.info("[knowledge_graph] 스킵 (매월 1일만 실행)")
        results.append({"name": "knowledge_graph", "status": "skipped", "reason": "not_1st"})

    # 요약
    log.info("=" * 60)
    for r in results:
        status = r["status"]
        elapsed = r.get("elapsed_sec", "-")
        log.info(f"  {r['name']:20s} {status:10s} ({elapsed}s)")
    log.info("=" * 60)

    success = sum(1 for r in results if r["status"] == "success")
    skipped = sum(1 for r in results if r["status"] == "skipped")
    failed = sum(1 for r in results if r["status"] not in ("success", "skipped"))
    log.info(f"결과: {success} 성공, {skipped} 스킵, {failed} 실패")

    return results


def schedule_loop():
    """스케줄 대기 모드 — 매일 새벽 3시에 실행"""
    import sched
    scheduler = sched.scheduler(time.time, time.sleep)

    def next_3am() -> float:
        now = datetime.now()
        target = now.replace(hour=3, minute=0, second=0, microsecond=0)
        if now >= target:
            target += timedelta(days=1)
        return (target - now).total_seconds()

    def scheduled_run():
        try:
            run_all_batches()
        except Exception as e:
            log.error(f"배치 실행 에러: {e}", exc_info=True)
        delay = next_3am()
        log.info(f"다음 실행: {delay / 3600:.1f}시간 후")
        scheduler.enter(delay, 1, scheduled_run)

    delay = next_3am()
    log.info(f"스케줄러 시작. 첫 실행: {delay / 3600:.1f}시간 후 "
             f"({(datetime.now() + timedelta(seconds=delay)).strftime('%m/%d %H:%M')})")
    scheduler.enter(delay, 1, scheduled_run)
    scheduler.run()


def dry_run():
    """실행하지 않고 순서만 표시"""
    now = datetime.now()
    dow = now.weekday()
    dom = now.day
    dow_names = ["월", "화", "수", "목", "금", "토", "일"]

    print(f"\n현재: {now.strftime('%Y-%m-%d %H:%M')} ({dow_names[dow]}요일)")
    print(f"{'='*50}")
    print(f"  03:00  야간 반성          (매일)        -> 실행")
    print(f"  04:00  토픽 마이닝         (일요일만)    -> {'실행' if dow == 6 else '스킵'}")
    print(f"  04:30  시계열 마이닝       (매일)        -> 실행")
    print(f"  05:00  행동 예측           (매일)        -> 실행")
    print(f"  05:00  지식 그래프         (매월 1일)    -> {'실행' if dom == 1 else '스킵'}")
    print(f"{'='*50}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="XREAL 통합 배치 스케줄러")
    parser.add_argument("--schedule", action="store_true", help="스케줄 대기 모드 (매일 03:00)")
    parser.add_argument("--dry-run", action="store_true", help="순서만 표시")
    args = parser.parse_args()

    if args.dry_run:
        dry_run()
    elif args.schedule:
        schedule_loop()
    else:
        run_all_batches()
