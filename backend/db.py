"""SQLite persistence."""
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Optional

from . import config


SCHEMA = """
CREATE TABLE IF NOT EXISTS oauth_tokens (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    refresh_token TEXT NOT NULL,
    access_token TEXT,
    expires_at INTEGER,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS work_blocks (
    event_id TEXT NOT NULL,
    instance_start INTEGER NOT NULL,
    instance_end INTEGER NOT NULL,
    title TEXT NOT NULL,
    last_seen INTEGER NOT NULL,
    PRIMARY KEY (event_id, instance_start)
);

CREATE INDEX IF NOT EXISTS idx_blocks_start ON work_blocks(instance_start);
CREATE INDEX IF NOT EXISTS idx_blocks_end ON work_blocks(instance_end);

CREATE TABLE IF NOT EXISTS poll_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    polled_at INTEGER NOT NULL,
    matched INTEGER NOT NULL,
    deleted INTEGER NOT NULL,
    error TEXT
);
"""


def init_db() -> None:
    Path(config.DB_PATH).parent.mkdir(parents=True, exist_ok=True)
    with connect() as conn:
        conn.executescript(SCHEMA)


@contextmanager
def connect():
    conn = sqlite3.connect(config.DB_PATH, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        yield conn
    finally:
        conn.close()


def save_tokens(refresh_token: str, access_token: Optional[str], expires_at: Optional[int]) -> None:
    import time
    with connect() as conn:
        conn.execute(
            """INSERT INTO oauth_tokens(id, refresh_token, access_token, expires_at, updated_at)
               VALUES(1, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET
                   refresh_token=excluded.refresh_token,
                   access_token=excluded.access_token,
                   expires_at=excluded.expires_at,
                   updated_at=excluded.updated_at""",
            (refresh_token, access_token, expires_at, int(time.time())),
        )


def get_tokens() -> Optional[sqlite3.Row]:
    with connect() as conn:
        cur = conn.execute("SELECT * FROM oauth_tokens WHERE id=1")
        return cur.fetchone()


def update_access_token(access_token: str, expires_at: int) -> None:
    import time
    with connect() as conn:
        conn.execute(
            "UPDATE oauth_tokens SET access_token=?, expires_at=?, updated_at=? WHERE id=1",
            (access_token, expires_at, int(time.time())),
        )


def upsert_blocks(blocks: list[dict]) -> None:
    import time
    now = int(time.time())
    with connect() as conn:
        conn.executemany(
            """INSERT INTO work_blocks(event_id, instance_start, instance_end, title, last_seen)
               VALUES(:event_id, :instance_start, :instance_end, :title, :last_seen)
               ON CONFLICT(event_id, instance_start) DO UPDATE SET
                   instance_end=excluded.instance_end,
                   title=excluded.title,
                   last_seen=excluded.last_seen""",
            [{**b, "last_seen": now} for b in blocks],
        )


def delete_stale_blocks(window_start: int, window_end: int, last_seen_before: int) -> int:
    with connect() as conn:
        cur = conn.execute(
            """DELETE FROM work_blocks
               WHERE instance_start >= ? AND instance_start < ?
               AND last_seen < ?""",
            (window_start, window_end, last_seen_before),
        )
        return cur.rowcount


def get_all_blocks() -> list[sqlite3.Row]:
    with connect() as conn:
        cur = conn.execute("SELECT * FROM work_blocks ORDER BY instance_start")
        return list(cur.fetchall())


def log_poll(matched: int, deleted: int, error: Optional[str] = None) -> None:
    import time
    with connect() as conn:
        conn.execute(
            "INSERT INTO poll_log(polled_at, matched, deleted, error) VALUES(?, ?, ?, ?)",
            (int(time.time()), matched, deleted, error),
        )


def latest_poll() -> Optional[sqlite3.Row]:
    with connect() as conn:
        cur = conn.execute("SELECT * FROM poll_log ORDER BY polled_at DESC LIMIT 1")
        return cur.fetchone()
