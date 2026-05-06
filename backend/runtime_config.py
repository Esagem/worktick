"""Mutable runtime config — reads from the app_config DB table, falling back to
env-var defaults from `config`. Use these helpers anywhere the value needs to be
live (poller, /schedule response, /admin/config). The env vars in `config.py` are
now bootstrap defaults only."""
from . import config, db


HOURLY_RATE_KEY = "hourly_rate"
WORK_EVENT_TITLE_KEY = "work_event_title"


def hourly_rate() -> float:
    val = db.get_config(HOURLY_RATE_KEY)
    if val is None:
        return config.HOURLY_RATE
    try:
        return float(val)
    except ValueError:
        return config.HOURLY_RATE


def work_event_title() -> str:
    val = db.get_config(WORK_EVENT_TITLE_KEY)
    if val is None or not val.strip():
        return config.WORK_EVENT_TITLE
    return val


def set_hourly_rate(rate: float) -> None:
    if rate < 0:
        raise ValueError("hourly_rate must be non-negative")
    db.set_config(HOURLY_RATE_KEY, str(rate))


def set_work_event_title(title: str) -> None:
    cleaned = title.strip()
    if not cleaned:
        raise ValueError("work_event_title must not be empty")
    db.set_config(WORK_EVENT_TITLE_KEY, cleaned)
