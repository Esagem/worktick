# WorkTick

A live "every penny accruing" widget for iOS and Android. Pulls events titled `McCrary Summer Work` from your Google Calendar, multiplies elapsed work time by your hourly rate, and shows the running gross dollar total.

## How it works

```
Google Calendar ──poll every 6 hours──> Backend (FastAPI on Fly.io)
                                            │
                                            ├─ SQLite: oauth tokens, work blocks, poll log
                                            └─ GET /schedule   →   {hourly_rate, blocks: [{start, end}, ...]}
                                                                          │
                       ┌──────────────────────────────────────────────────┴────────────────────────────────┐
                       ▼                                                                                   ▼
            Android home screen widget                                              iOS — three places to see the ticker
            • RemoteViews + AlarmManager                                            • Home screen widget    (Watch the dollars climb every few minutes)
            • 1-second re-render when active                                        • Lock screen Live      (1 Hz penny tick, lasts the work block)
            • All math on device                                                    • Dynamic Island        (compact penny tick when app open)
```

The big design choice: **the backend returns just the schedule, not the totals.** Widgets compute everything locally each tick. This keeps the live counter fluid (no network round-trips) and means it keeps working even if the backend is briefly unreachable.

The backend polls Google Calendar every 6 hours because work schedules don't change often. This keeps the Fly.io machine quiet and avoids hammering Google's quota.

## A note on iOS tick rates

Apple does not allow arbitrary widget content to update at 1 Hz on the home screen. The OS does support 1 Hz updates for `Text(timerInterval:)` (which is what the home screen widget uses for the elapsed-time line). The dollar amount on the home screen widget refreshes every 2 minutes during active work blocks — fast enough to see climbing, but not penny-by-penny.

For a true penny-by-penny live tick on iOS, the project includes a **Live Activity** that runs on the lock screen and Dynamic Island. Live Activities can update at 1 Hz, and the host app starts one automatically when a work block becomes active.

Android has no such restriction — `MoneyTickerWidgetProvider` uses `AlarmManager` to schedule a re-render every second while a block is active, and you'll see the penny ticker on the home screen directly.

---

## Step 1 — Google OAuth credentials

1. https://console.cloud.google.com/ → create a project (e.g. "WorkTick").
2. **APIs & Services → Library** → enable **Google Calendar API**.
3. **OAuth consent screen** → External → fill required fields → add yourself as a test user.
4. **Important:** click **Publish App**. In testing mode, refresh tokens expire after 7 days. Publishing makes them long-lived. Google won't actually verify the app because you're the only user.
5. **Credentials → Create Credentials → OAuth client ID** → Web application.
6. Authorized redirect URI: `https://YOUR-FLY-APP.fly.dev/oauth/callback`
7. Save the Client ID and Client Secret.

## Step 2 — Deploy the backend

```bash
curl -L https://fly.io/install.sh | sh

cd backend
fly auth signup    # or `fly auth login`
fly launch --copy-config --no-deploy   # accept defaults; pick a unique app name
fly volumes create worktick_data --size 1 --region atl

# Set secrets — note HOURLY_RATE goes here so it isn't in your repo
fly secrets set \
  GOOGLE_CLIENT_ID="<from step 1>" \
  GOOGLE_CLIENT_SECRET="<from step 1>" \
  GOOGLE_REDIRECT_URI="https://YOUR-APP.fly.dev/oauth/callback" \
  HOURLY_RATE="15.00" \
  API_SHARED_SECRET="$(python -c 'import secrets;print(secrets.token_urlsafe(32))')"

# Print and save the secret value (you'll paste it into the widgets):
fly secrets list

fly deploy
```

## Step 3 — Connect Google

Visit `https://YOUR-APP.fly.dev/oauth/start` in your browser. Sign in with the Google account that has the `McCrary Summer Work` events. After redirect you'll see a "connected" page.

Sanity check:
```bash
SECRET="<your API_SHARED_SECRET>"
curl -H "Authorization: Bearer $SECRET" https://YOUR-APP.fly.dev/schedule | jq
```

You should see your hourly rate and a list of work blocks. If `blocks` is empty, double-check the event title is exactly `McCrary Summer Work` (case-insensitive).

## Step 4 — iOS

Requires: Mac with Xcode 15+, paid Apple Developer account ($99/yr) for permanent install. Free signing works but expires every 7 days, which means re-installing weekly.

1. Xcode → New Project → App → Swift / SwiftUI → bundle ID e.g. `dev.surge.worktick`.
2. Delete the auto-generated `ContentView.swift` and `WorkTickApp.swift` from the app target. Drop in `ios/WorkTickHostApp.swift`.
3. **Capabilities (app target):**
   - Background Modes → check "Background fetch"
   - Add to `Info.plist`: a `NSSupportsLiveActivities` key set to `YES`
   - Add to `Info.plist`: a `BGTaskSchedulerPermittedIdentifiers` array containing `dev.surge.worktick.refresh`
4. File → New → Target → **Widget Extension** → name `WorkTickWidgetExtension`. Uncheck "Include Configuration Intent". Check "Include Live Activity".
5. Replace the auto-generated widget bundle's contents with `ios/WorkTickWidget.swift` and `ios/WorkTickLiveActivity.swift`.
6. Make sure the widget bundle's main file declares both:
   ```swift
   @main
   struct WorkTickWidgetBundle: WidgetBundle {
       var body: some Widget {
           WorkTickWidget()
           WTLiveActivity()
       }
   }
   ```
7. Edit `WT.backendURL` and `WT.apiSecret` at the top of `WorkTickWidget.swift`. The host app references the same types — in Xcode's File Inspector for `WorkTickWidget.swift`, check both the app target *and* the widget extension target so the types are visible to both.
8. Connect iPhone → run the app target once → close it.
9. Long-press home screen → Add Widget → search "WorkTick" → add.
10. iOS Settings → WorkTick → Live Activities → ensure enabled.

When a `McCrary Summer Work` block becomes active, opening the app will start a Live Activity. It'll appear on your lock screen and in the Dynamic Island, ticking up by the penny.

## Step 5 — Android

1. Android Studio → New Project → Empty Activity → package `dev.surge.worktick`, min SDK 26.
2. Add to `app/build.gradle.kts`:
   ```kotlin
   dependencies {
       implementation("androidx.work:work-runtime-ktx:2.9.1")
       implementation("com.squareup.okhttp3:okhttp:4.12.0")
       implementation("androidx.core:core-ktx:1.13.1")
   }
   ```
3. Drop the Kotlin files from `android/` into `app/src/main/java/dev/surge/worktick/`:
   - `Schedule.kt`, `Math.kt`, `ScheduleFetchWorker.kt`, `MoneyTickerWidgetProvider.kt`, `WorkTickApp.kt`
4. Drop `res_layout_worktick_money.xml` → `app/src/main/res/layout/worktick_money.xml`
5. Drop `res_xml_worktick_money_info.xml` → `app/src/main/res/xml/worktick_money_info.xml`
6. Merge `AndroidManifest_snippet.xml` into your `AndroidManifest.xml`. Set `android:name=".WorkTickApp"` on the `<application>` tag.
7. Edit `WTConfig` in `ScheduleFetchWorker.kt` with your backend URL and API secret.
8. Build & run on device. Long-press home → Widgets → WorkTick → drag to home.

The widget will be silent at $0.00 until the first WorkManager fetch (within 15 min) or until you tap it (which forces a refresh).

---

## Things to tweak

- **Hourly rate change:** `fly secrets set HOURLY_RATE="20.00"` then `fly deploy`. Widgets pick it up at next fetch. The on-device tick rate auto-scales: at $30/hr the live ticker fires every 0.6s (1.7 Hz), at $60/hr every 0.3s (3.3 Hz), capped at 4 Hz. So the counter stays visually fluid even if you raise.
- **Different event title:** `fly secrets set WORK_EVENT_TITLE="Some Other Title"` then redeploy.
- **Polling more often:** `fly secrets set POLL_INTERVAL_SECONDS="3600"` for hourly.
- **Inspect what's in the DB:** `curl -H "Authorization: Bearer $SECRET" https://YOUR-APP.fly.dev/debug/blocks | jq` (if you re-add that endpoint — currently removed from main.py for tidiness).

## Cost

- Fly.io: free tier covers this easily (one shared-cpu-1x machine + 1GB volume).
- Google Calendar API: free.
- Apple Developer: $99/yr if you want the iOS widget to stick. Skip if you're OK re-installing weekly via free signing.
- Total: $0 if Android-only, $0–8/mo with iOS.
