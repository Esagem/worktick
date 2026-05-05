# WorkTick

A live "every penny accruing" widget for Android (and iOS in progress). Pulls events titled `McCrary Summer Work` from Google Calendar, multiplies elapsed work time by your hourly rate, and shows the running gross dollar total.

## Architecture

```
Google Calendar ──poll every 6h──> Backend (FastAPI on Fly.io)
                                       │
                                       ├─ SQLite: oauth tokens, work blocks, poll log
                                       └─ GET /schedule   → {hourly_rate, blocks: [{start, end}, ...]}
                                                                 │
                              ┌──────────────────────────────────┴─────────────────┐
                              ▼                                                    ▼
                Android home widget (1 Hz when active block,                   iOS (in progress)
                ~30 min otherwise)
                  + foreground service for sub-second ticks
                  + tap to open SmoothActivity (60 fps view)
```

The backend returns just the schedule, not the totals. The widget computes elapsed time and dollar amounts locally on every render. Network only happens every 6 hours.

## Repo layout

```
.
├── backend/              FastAPI service. Deploy to Fly.io.
├── android/              Android Studio project (open the android/ folder).
├── ios/                  iOS sources (in progress).
├── docs/
│   ├── SECURITY.md       Secrets handling, rotation playbook.
│   ├── BACKEND.md        Backend setup walkthrough.
│   ├── ANDROID.md        Android setup walkthrough.
│   └── IOS.md            iOS setup walkthrough.
├── .gitleaks.toml        Secret scanning rules.
├── .githooks/pre-commit  Pre-commit secret check.
└── .github/workflows/    CI: gitleaks on every push.
```

## Quick start

1. **Read [`docs/SECURITY.md`](docs/SECURITY.md) first.** This project has secrets. Set them up correctly before doing anything else.

2. **Enable the local pre-commit hook:**
   ```bash
   git config core.hooksPath .githooks
   ```

3. **Deploy the backend:** see [`docs/BACKEND.md`](docs/BACKEND.md).

4. **Build the Android app:** see [`docs/ANDROID.md`](docs/ANDROID.md).

## Status

- [x] Backend on Fly.io
- [x] Google OAuth + Calendar polling
- [x] Android home widget (~1 Hz with foreground service)
- [x] Android tap-to-open 60 fps `SmoothActivity`
- [x] Block-boundary alarms (auto start/stop service)
- [ ] iOS WidgetKit + Live Activity
- [ ] Lock screen / AOD via Samsung LockStar (works automatically with existing widget)
- [ ] Optional: foreground-service overlay bubble for true 60 fps on home screen

## Cost

- Fly.io: free tier covers it.
- Google Calendar API: free.
- Total: $0/mo.
