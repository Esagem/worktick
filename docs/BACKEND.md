# Backend Setup

The backend is a FastAPI service that polls Google Calendar every 6 hours and serves the schedule to the widgets.

## Prerequisites

- Python 3.12+ (for local dev only)
- A Fly.io account (free tier works)
- A Google Cloud project with the Calendar API enabled

## 1. Google OAuth credentials

1. https://console.cloud.google.com/ → create project → enable **Google Calendar API**
2. APIs & Services → OAuth consent screen → External → fill required fields → add yourself as a test user → **Publish App** (avoids 7-day token expiry)
3. APIs & Services → Credentials → Create Credentials → OAuth client ID → Web application
4. Authorized redirect URI: `https://YOUR-FLY-APP.fly.dev/oauth/callback`
5. Save the Client ID and Client Secret. **The secret only displays once.**

## 2. Deploy to Fly.io

```bash
curl -L https://fly.io/install.sh | sh

cd backend
fly auth signup
```

Edit `fly.toml` line 1: change `app = "worktick-CHANGEME"` to a globally unique name (e.g., `worktick-yourname`).

```bash
fly launch --copy-config --no-deploy
fly volumes create worktick_data --size 1 --region atl
```

Set secrets — generate API_SHARED_SECRET first:

```bash
SECRET=$(python -c "import secrets; print(secrets.token_urlsafe(32))")
echo "Save this for Android/iOS: $SECRET"

fly secrets set \
  GOOGLE_CLIENT_ID="<from step 1>" \
  GOOGLE_CLIENT_SECRET="<from step 1>" \
  GOOGLE_REDIRECT_URI="https://YOUR-FLY-APP.fly.dev/oauth/callback" \
  HOURLY_RATE="30.00" \
  API_SHARED_SECRET="$SECRET"

fly deploy
```

## 3. Connect Google Calendar

In a browser: `https://YOUR-FLY-APP.fly.dev/oauth/start` → sign in → past the warning → grant access. You should land on a green ✓ page.

## 4. Verify

```bash
curl -H "Authorization: Bearer $SECRET" https://YOUR-FLY-APP.fly.dev/schedule | python -m json.tool
```

You should see your hourly rate and a list of work blocks.

## Local development

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

cp .env.example .env
# Edit .env with real values (Client ID, Secret, etc.)

# Load .env and run
set -a; source .env; set +a
uvicorn backend.main:app --reload --port 8000
```

Then visit `http://localhost:8000/oauth/start`.

To reach the local backend from a phone (for Android testing), use Cloudflare Tunnel:
```bash
cloudflared tunnel --url http://localhost:8000
```

## Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/` | None | Health + last poll info |
| GET | `/oauth/start` | None | Start Google OAuth flow |
| GET | `/oauth/callback` | None | OAuth redirect target |
| GET | `/schedule` | Bearer | Schedule + hourly rate |
| GET | `/debug/blocks` | Bearer | Raw block rows |
| POST | `/admin/poll` | Bearer | Force a poll |

## Operations

```bash
# Check logs
fly logs

# SSH into the running machine
fly ssh console

# Inspect the SQLite DB
fly ssh console -C "sqlite3 /data/worktick.db .tables"

# Force a poll
curl -X POST -H "Authorization: Bearer $SECRET" https://YOUR-FLY-APP.fly.dev/admin/poll

# Change hourly rate
fly secrets set HOURLY_RATE="35.00"
fly deploy
```
