"""FastAPI entrypoint."""
import logging
import secrets
from contextlib import asynccontextmanager

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import FastAPI, HTTPException, Header, Query, Response
from fastapi.responses import HTMLResponse, RedirectResponse

from . import config, db, google_client, poller


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger(__name__)

_oauth_states: set[str] = set()


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    scheduler = AsyncIOScheduler()
    scheduler.add_job(
        _safe_poll, "interval",
        seconds=config.POLL_INTERVAL_SECONDS,
        next_run_time=None,
        id="calendar_poll", max_instances=1, coalesce=True,
    )
    scheduler.start()
    log.info("Scheduler started; polling every %ds (%.1fh)",
             config.POLL_INTERVAL_SECONDS, config.POLL_INTERVAL_SECONDS / 3600)
    yield
    scheduler.shutdown()


def _safe_poll():
    try:
        poller.poll_once()
    except Exception:
        log.exception("Poll failed")


app = FastAPI(title="WorkTick", lifespan=lifespan)


def _check_secret(authorization: str | None) -> None:
    if not config.API_SHARED_SECRET:
        return
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Missing bearer token")
    token = authorization.removeprefix("Bearer ").strip()
    if not secrets.compare_digest(token, config.API_SHARED_SECRET):
        raise HTTPException(401, "Bad token")


@app.get("/")
def root():
    tokens = db.get_tokens()
    last = db.latest_poll()
    return {
        "ok": True,
        "authenticated": tokens is not None,
        "work_event_title": config.WORK_EVENT_TITLE,
        "poll_interval_seconds": config.POLL_INTERVAL_SECONDS,
        "last_poll": dict(last) if last else None,
    }


@app.get("/oauth/start")
def oauth_start():
    state = secrets.token_urlsafe(16)
    _oauth_states.add(state)
    return RedirectResponse(google_client.build_auth_url(state))


@app.get("/oauth/callback", response_class=HTMLResponse)
def oauth_callback(code: str = Query(...), state: str = Query(...)):
    if state not in _oauth_states:
        raise HTTPException(400, "Bad state")
    _oauth_states.discard(state)
    google_client.exchange_code(code)
    try:
        stats = poller.poll_once()
    except Exception as e:
        log.exception("Initial poll after auth failed")
        stats = {"error": str(e)}
    return f"""<!doctype html><meta charset=utf-8>
    <title>WorkTick connected</title>
    <body style="font-family:system-ui;max-width:600px;margin:3em auto;padding:1em">
    <h1>✓ Google Calendar connected</h1>
    <p>Initial poll: <code>{stats}</code></p>
    <p>You can close this tab.</p>
    </body>"""


@app.post("/admin/poll")
def force_poll(authorization: str | None = Header(None)):
    _check_secret(authorization)
    return poller.poll_once()


@app.get("/schedule")
def schedule(response: Response, authorization: str | None = Header(None)):
    """Return the full block list and config. Widget computes totals locally."""
    _check_secret(authorization)
    if not db.get_tokens():
        raise HTTPException(503, "Not authenticated. Visit /oauth/start.")

    rows = db.get_all_blocks()
    last = db.latest_poll()

    response.headers["Cache-Control"] = f"private, max-age={config.SCHEDULE_MAX_AGE_SECONDS}"

    return {
        "fetched_at": last["polled_at"] if last else None,
        "timezone": str(config.TIMEZONE),
        "hourly_rate": config.HOURLY_RATE,
        "blocks": [
            {"start": r["instance_start"], "end": r["instance_end"]}
            for r in rows
        ],
    }


@app.get("/debug/blocks")
def debug_blocks(authorization: str | None = Header(None)):
    """Inspect raw block rows including event_id and last_seen timestamps."""
    _check_secret(authorization)
    rows = db.get_all_blocks()
    return [dict(r) for r in rows]
