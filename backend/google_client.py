"""Google OAuth and Calendar API client."""
import time
import urllib.parse
from typing import Optional

import requests

from . import config, db


SCOPES = ["https://www.googleapis.com/auth/calendar.readonly"]
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
EVENTS_URL = "https://www.googleapis.com/calendar/v3/calendars/{calendar_id}/events"


def build_auth_url(state: str) -> str:
    params = {
        "client_id": config.GOOGLE_CLIENT_ID,
        "redirect_uri": config.GOOGLE_REDIRECT_URI,
        "response_type": "code",
        "scope": " ".join(SCOPES),
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    }
    return f"{AUTH_URL}?{urllib.parse.urlencode(params)}"


def exchange_code(code: str) -> dict:
    resp = requests.post(
        TOKEN_URL,
        data={
            "code": code,
            "client_id": config.GOOGLE_CLIENT_ID,
            "client_secret": config.GOOGLE_CLIENT_SECRET,
            "redirect_uri": config.GOOGLE_REDIRECT_URI,
            "grant_type": "authorization_code",
        },
        timeout=10,
    )
    resp.raise_for_status()
    payload = resp.json()
    refresh = payload.get("refresh_token")
    access = payload.get("access_token")
    expires_at = int(time.time()) + int(payload.get("expires_in", 3600)) - 60
    if not refresh:
        raise RuntimeError("No refresh_token returned. Revoke app access in Google account and re-auth.")
    db.save_tokens(refresh, access, expires_at)
    return payload


def get_access_token() -> str:
    row = db.get_tokens()
    if not row:
        raise RuntimeError("Not authenticated. Visit /oauth/start to connect Google.")
    if row["access_token"] and row["expires_at"] and row["expires_at"] > int(time.time()) + 30:
        return row["access_token"]
    resp = requests.post(
        TOKEN_URL,
        data={
            "refresh_token": row["refresh_token"],
            "client_id": config.GOOGLE_CLIENT_ID,
            "client_secret": config.GOOGLE_CLIENT_SECRET,
            "grant_type": "refresh_token",
        },
        timeout=10,
    )
    resp.raise_for_status()
    payload = resp.json()
    access = payload["access_token"]
    expires_at = int(time.time()) + int(payload.get("expires_in", 3600)) - 60
    db.update_access_token(access, expires_at)
    return access


def list_events(calendar_id: str, time_min_iso: str, time_max_iso: str) -> list[dict]:
    token = get_access_token()
    events: list[dict] = []
    page_token: Optional[str] = None
    while True:
        params = {
            "timeMin": time_min_iso,
            "timeMax": time_max_iso,
            "singleEvents": "true",
            "orderBy": "startTime",
            "maxResults": 250,
            "showDeleted": "false",
        }
        if page_token:
            params["pageToken"] = page_token
        resp = requests.get(
            EVENTS_URL.format(calendar_id=urllib.parse.quote(calendar_id)),
            params=params,
            headers={"Authorization": f"Bearer {token}"},
            timeout=15,
        )
        resp.raise_for_status()
        data = resp.json()
        events.extend(data.get("items", []))
        page_token = data.get("nextPageToken")
        if not page_token:
            break
    return events


def list_calendars() -> list[dict]:
    token = get_access_token()
    resp = requests.get(
        "https://www.googleapis.com/calendar/v3/users/me/calendarList",
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json().get("items", [])
