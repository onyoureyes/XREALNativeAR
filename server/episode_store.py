#!/usr/bin/env python3
"""
XREAL Episode Store — L2 에피소드 영속 저장

에피소드(L1 워킹 메모리)의 SQLite 백업.
서버 재시작 시에도 에피소드가 유실되지 않도록 보장.

Orchestrator가 _add_episode() 시 여기에도 동시 기록.
"""

import json
import sqlite3
import time
import logging
from pathlib import Path
from datetime import datetime

log = logging.getLogger("episode_store")

DB_PATH = Path("./data/episodes.db")


class EpisodeStore:
    """L2 에피소드 영속 저장 (SQLite)"""

    def __init__(self, db_path: str = None):
        self.db_path = Path(db_path) if db_path else DB_PATH
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_db()

    def _init_db(self):
        conn = sqlite3.connect(str(self.db_path))
        conn.execute("""
            CREATE TABLE IF NOT EXISTS episodes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp REAL NOT NULL,
                metadata TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_episodes_ts ON episodes(timestamp DESC)
        """)
        conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_episodes_type ON episodes(event_type)
        """)
        conn.commit()
        conn.close()
        log.info(f"EpisodeStore 초기화: {self.db_path}")

    def save(self, event_type: str, content: str, timestamp: float,
             metadata: dict = None):
        """에피소드 저장"""
        conn = sqlite3.connect(str(self.db_path))
        try:
            conn.execute(
                "INSERT INTO episodes (event_type, content, timestamp, metadata) VALUES (?, ?, ?, ?)",
                (event_type, content, timestamp, json.dumps(metadata) if metadata else None),
            )
            conn.commit()
        except Exception as e:
            log.error(f"에피소드 저장 실패: {e}")
        finally:
            conn.close()

    def get_recent(self, limit: int = 50) -> list[dict]:
        """최근 에피소드 조회"""
        conn = sqlite3.connect(str(self.db_path))
        try:
            rows = conn.execute(
                "SELECT id, event_type, content, timestamp, metadata FROM episodes ORDER BY timestamp DESC LIMIT ?",
                (limit,),
            ).fetchall()
            return [
                {
                    "id": r[0],
                    "event_type": r[1],
                    "content": r[2],
                    "timestamp": r[3],
                    "metadata": json.loads(r[4]) if r[4] else None,
                }
                for r in reversed(rows)  # 시간순 정렬
            ]
        finally:
            conn.close()

    def get_by_date(self, date_str: str) -> list[dict]:
        """특정 날짜 에피소드 조회 (YYYY-MM-DD)"""
        dt = datetime.strptime(date_str, "%Y-%m-%d")
        start_ts = dt.timestamp()
        end_ts = start_ts + 86400

        conn = sqlite3.connect(str(self.db_path))
        try:
            rows = conn.execute(
                "SELECT id, event_type, content, timestamp, metadata FROM episodes WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp",
                (start_ts, end_ts),
            ).fetchall()
            return [
                {
                    "id": r[0],
                    "event_type": r[1],
                    "content": r[2],
                    "timestamp": r[3],
                    "metadata": json.loads(r[4]) if r[4] else None,
                }
                for r in rows
            ]
        finally:
            conn.close()

    def count(self) -> int:
        """총 에피소드 수"""
        conn = sqlite3.connect(str(self.db_path))
        try:
            return conn.execute("SELECT COUNT(*) FROM episodes").fetchone()[0]
        finally:
            conn.close()

    def get_daily_stats(self, days: int = 7) -> list[dict]:
        """최근 N일간 일별 통계"""
        conn = sqlite3.connect(str(self.db_path))
        try:
            rows = conn.execute("""
                SELECT DATE(timestamp, 'unixepoch') as day,
                       event_type,
                       COUNT(*) as cnt
                FROM episodes
                WHERE timestamp >= ?
                GROUP BY day, event_type
                ORDER BY day DESC
            """, (time.time() - days * 86400,)).fetchall()

            stats = {}
            for day, etype, cnt in rows:
                if day not in stats:
                    stats[day] = {"date": day, "total": 0, "by_type": {}}
                stats[day]["total"] += cnt
                stats[day]["by_type"][etype] = cnt

            return list(stats.values())
        finally:
            conn.close()
