"""Periodic poller. Walks all calendars, finds matching events, upserts to DB."""
import logging
import time
from datetime import datetime, timedelta, timezone

from . import config, db, google_client


log = logging.getLogger(__name__)


def _parse_event_time(t: dict) -> int:
    if "dateTime" in t:
        return int(datetime.fromisoformat(t["dateTime"].replace("Z", "+00:00")).timestamp())
    if "date" in t:
        d = datetime.fromisoformat(t["date"]).replace(tzinfo=config.TIMEZONE)
        return int(d.timestamp())
    raise ValueError(f"Unrecognized time payload: {t}")


def _matches_work_title(event: dict) -> bool:
    summary = (event.get("summary") or "").strip().casefold()
    target = config.WORK_EVENT_TITLE.strip().casefold()
    return summary == target


def poll_once() -> dict:
    poll_started = int(time.time())
    err: str | None = None
    matched_blocks: list[dict] = []
    deleted = 0

    try:
        now = datetime.now(timezone.utc)
        time_min = now - timedelta(days=config.LOOKBACK_DAYS)
        time_max = now + timedelta(days=config.LOOKAHEAD_DAYS)

        window_start = int(time_min.timestamp())
        window_end = int(time_max.timestamp())

        calendars = google_client.list_calendars()
        log.info("Polling %d calendars", len(calendars))

        for cal in calendars:
            try:
                events = google_client.list_events(
                    cal["id"],
                    time_min.isoformat().replace("+00:00", "Z"),
                    time_max.isoformat().replace("+00:00", "Z"),
                )
            except Exception as e:
                log.warning("Failed to list events for calendar %s: %s", cal.get("id"), e)
                continue

            for ev in events:
                if not _matches_work_title(ev):
                    continue
                try:
                    start_ts = _parse_event_time(ev["start"])
                    end_ts = _parse_event_time(ev["end"])
                except (KeyError, ValueError) as e:
                    log.warning("Skipping malformed event %s: %s", ev.get("id"), e)
                    continue
                if end_ts <= start_ts:
                    continue
                matched_blocks.append({
                    "event_id": ev.get("iCalUID") or ev["id"],
                    "instance_start": start_ts,
                    "instance_end": end_ts,
                    "title": ev.get("summary", ""),
                })

        if matched_blocks:
            db.upsert_blocks(matched_blocks)
        deleted = db.delete_stale_blocks(window_start, window_end, poll_started)
    except Exception as e:
        err = str(e)
        log.exception("Poll failed")

    db.log_poll(len(matched_blocks), deleted, err)
    stats = {
        "matched": len(matched_blocks),
        "deleted_stale": deleted,
        "polled_at": poll_started,
        "error": err,
    }
    log.info("Poll complete: %s", stats)
    return stats
