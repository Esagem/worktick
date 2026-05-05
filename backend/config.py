"""Configuration loaded from environment variables.

For local development, copy .env.example to .env and fill in real values.
For production (Fly.io), all values are set via `fly secrets set`.
"""
import os
from zoneinfo import ZoneInfo


# Google OAuth
GOOGLE_CLIENT_ID = os.environ.get("GOOGLE_CLIENT_ID", "")
GOOGLE_CLIENT_SECRET = os.environ.get("GOOGLE_CLIENT_SECRET", "")
GOOGLE_REDIRECT_URI = os.environ.get(
    "GOOGLE_REDIRECT_URI", "http://localhost:8000/oauth/callback"
)

# What event title counts as "work" — case-insensitive, whitespace-trimmed
WORK_EVENT_TITLE = os.environ.get("WORK_EVENT_TITLE", "McCrary Summer Work")

# Timezone for parsing all-day events (widgets currently don't depend on this)
TIMEZONE = ZoneInfo(os.environ.get("TIMEZONE", "America/Chicago"))

# Hourly wage in USD (gross). Widgets multiply elapsed-seconds * RATE / 3600 client-side.
HOURLY_RATE = float(os.environ.get("HOURLY_RATE", "0"))

# Database
DB_PATH = os.environ.get("DB_PATH", "/data/worktick.db")

# Calendar polling. Schedules are stable → 6 hours is plenty.
POLL_INTERVAL_SECONDS = int(os.environ.get("POLL_INTERVAL_SECONDS", str(6 * 3600)))

# API auth — shared secret sent by widgets in `Authorization: Bearer`
API_SHARED_SECRET = os.environ.get("API_SHARED_SECRET", "")

# Calendar query window. Generous lookback so all-time totals don't lose
# events that scrolled out of a narrower window.
LOOKBACK_DAYS = int(os.environ.get("LOOKBACK_DAYS", "365"))
LOOKAHEAD_DAYS = int(os.environ.get("LOOKAHEAD_DAYS", "14"))

# Cache hint sent on /schedule responses
SCHEDULE_MAX_AGE_SECONDS = int(os.environ.get("SCHEDULE_MAX_AGE_SECONDS", str(6 * 3600)))
