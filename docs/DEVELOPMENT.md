# Local development

## Backend

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

export GOOGLE_CLIENT_ID="..."
export GOOGLE_CLIENT_SECRET="..."
export GOOGLE_REDIRECT_URI="http://localhost:8000/oauth/callback"
export DB_PATH="./worktick.db"
export API_SHARED_SECRET="dev-secret"
export HOURLY_RATE="15.00"

uvicorn backend.main:app --reload --port 8000
```

Then visit `http://localhost:8000/oauth/start`.

## Reaching the local backend from a phone

iOS widgets refuse plain HTTP and won't see `localhost`. Two options:

- **Cloudflare Tunnel** (free, no signup): `cloudflared tunnel --url http://localhost:8000`
- **ngrok**: `ngrok http 8000`

Either gives you an HTTPS URL you can paste into the widget config for testing.

## Forcing a poll

```bash
curl -X POST -H "Authorization: Bearer dev-secret" http://localhost:8000/admin/poll
```

## Inspecting state

```bash
curl -H "Authorization: Bearer dev-secret" http://localhost:8000/schedule | jq
curl -H "Authorization: Bearer dev-secret" http://localhost:8000/debug/blocks | jq
```
