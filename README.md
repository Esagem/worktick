# WorkTick

A live "every penny accruing" earnings widget for Android and iOS. Finds calendar events titled `McCrary Summer Work` (configurable), multiplies elapsed work time by your hourly rate, and shows the running gross dollar total — updating sub-second on the home screen while a block is active.

No server. Each client reads the calendar directly on-device.

## Architecture

```
ANDROID                                        iOS
────────────                                   ────────────
Google Calendar API                            iOS Calendar (device)
      ▲                                              ▲
      │ OAuth (Identity Authorization)               │ EventKit
      │ refresh every 6h via WorkManager             │ refresh on demand
      │                                              │
SchedulePoller / GoogleCalendarClient          WTEventKitPoller
      │                                              │
      ▼                                              ▼
ScheduleStore (file)                           WTScheduleStore (App Group file)
      │                                              │
      ▼                                              ▼
MoneyTickerWidgetProvider                      WorkTickWidget
WidgetTickerService (~1 Hz when active)        WorkTickLiveActivity (Lock + Dynamic Island)
BlockBoundaryReceiver (exact alarms)           WTLiveActivityController
SmoothActivity (60 fps tap-to-open)            SmoothTickerView (60 fps)
```

Both clients compute elapsed time and dollars locally on every render. Only the schedule is fetched — never the totals.

## Repo layout

```
.
├── android/                  Android Studio project. minSdk 26, targetSdk 34.
│   └── app/src/main/java/dev/surge/worktick/
│       ├── auth/GoogleAuthManager.kt         OAuth via Google Identity Authorization API
│       ├── auth/TokenStore.kt                Encrypted refresh/access token storage
│       ├── calendar/GoogleCalendarClient.kt  Direct Calendar API client (OkHttp)
│       ├── calendar/SchedulePoller.kt        Walks calendars, filters by event title
│       ├── ScheduleFetchWorker.kt            WorkManager-backed 6h poll + retry/backoff
│       ├── MoneyTickerWidgetProvider.kt      Home widget (4×1 "Terminal" face)
│       ├── WidgetTickerService.kt            Foreground service, ~1 Hz when screen on
│       ├── BlockBoundaryReceiver.kt          Exact alarms at block start/end
│       ├── SmoothActivity.kt + MoneyView     60 fps Canvas detail view (tap-to-open)
│       ├── SettingsActivity.kt               Hourly rate, event title, battery exemption
│       └── WTSettings.kt                     SharedPreferences persistence
│
├── ios/                      Xcode project sources. iOS 16.1+ (17+ recommended).
│   ├── Shared/               Pure logic shared with widget extension
│   │                         (WTSchedule, WTMath, WTBlockState, WTFormat, WTActivityAttributes)
│   ├── Platform/             Host-app glue
│   │   ├── WTEventKitPoller.swift          Reads selected calendars via EventKit
│   │   ├── WTScheduleStore.swift           App Group file cache
│   │   ├── WTBackgroundScheduler.swift     BackgroundTasks refresh
│   │   ├── WTLiveActivityController.swift  Live Activity / Dynamic Island lifecycle
│   │   └── WTSettings.swift                UserDefaults via group.dev.surge.worktick
│   ├── App/                  Host app target (DashboardView, SettingsView, SmoothTickerView)
│   ├── WidgetExtension/      WorkTickWidget + WorkTickLiveActivity
│   └── WorkTickConfig.example.swift        Copy → WorkTickConfig.swift (gitignored)
│
├── docs/
│   ├── SECURITY.md           Secrets handling, rotation playbook, gitleaks setup
│   ├── ANDROID.md            Android setup, signing, Google auth
│   └── IOS.md                Xcode project creation, capabilities, widget target
│
├── .gitleaks.toml            Custom rules for Google OAuth secrets
├── .githooks/pre-commit      Local secret scan (regex pre-commit guard)
└── .github/workflows/        CI: gitleaks on push/PR
```

## Quick start

1. **Read [`docs/SECURITY.md`](docs/SECURITY.md) first.** Android needs a Google OAuth client ID/secret. Set them up correctly before building.

2. **Enable the local pre-commit hook:**
   ```bash
   git config core.hooksPath .githooks
   ```

3. **Build the Android app:** see [`docs/ANDROID.md`](docs/ANDROID.md).

4. **Build the iOS app:** see [`docs/IOS.md`](docs/IOS.md). Grant Calendar access and pick which calendars to scan.

## How the calendar fetch works

**Android.** `GoogleAuthManager` runs an OAuth flow via Google's Identity Authorization API to obtain a refresh token with the `calendar.readonly` scope; tokens are persisted in `TokenStore` (EncryptedSharedPreferences). `SchedulePoller` lists every visible calendar, fetches events in a ±window around now (lookback 365 d, lookahead 14 d), filters by event title (case-insensitive, trimmed), dedupes by start time across shared calendars, and writes a `Schedule` to `ScheduleStore`. `ScheduleFetchWorker` runs this every 6 hours via WorkManager with retry/backoff.

**iOS.** `WTEventKitPoller` reads the selected `EKCalendar`s via EventKit. No network, no OAuth — the user grants Calendar permission once. `WTBackgroundScheduler` triggers refreshes via the BackgroundTasks framework.

## Battery behavior

**Android.** The widget ticks at ~1 Hz only while a work block is active **and** the screen is on. `WidgetTickerService` checks `Display.STATE_ON` and the keyguard each tick and skips render when the device is off / locked / on AOD (with diagnostic counters for verification). Schedule fetches run through WorkManager with backoff, not a persistent polling loop. The app prompts for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` so vendor battery-savers don't kill the foreground service mid-block.

**iOS.** Widget refresh uses `TimelineView(.periodic(...))` (1–5 s on iOS 17+, system-throttled). Live Activity is opt-in from Settings; `Text(timerInterval:...)` drives elapsed-time display so the system handles per-second redraws. `WTLiveActivityController.reconcile()` is idempotent and adopts in-flight activities on cold launch.

## Status

- [x] Android home widget with ~1 Hz ticking via foreground service
- [x] Android tap-to-open 60 fps `SmoothActivity`
- [x] Android block-boundary exact alarms (auto start/stop service)
- [x] Android battery optimizations (WorkManager backoff, screen-off skip, AOD detect)
- [x] Android direct Google Calendar OAuth (no server)
- [x] iOS widget (home screen) via WidgetKit
- [x] iOS Live Activity + Dynamic Island (opt-in)
- [x] iOS direct EventKit integration
- [ ] Lock screen / AOD widget face on Samsung LockStar
- [ ] Foreground-service overlay bubble for true 60 fps on Android home screen

## Cost

- Google Calendar API: free.
- Apple developer account: $0 for personal sideload (7-day re-sign), $99/yr for permanent install.
- Total: $0/mo.
