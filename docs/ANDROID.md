# Android Setup

## Prerequisites

- Android Studio (Hedgehog or newer)
- Android device with USB debugging on (or an emulator with API 26+)
- Backend deployed and reachable (see [BACKEND.md](BACKEND.md))

## 1. Open the project

In Android Studio: **File → Open** → select the `android/` folder of this repo (NOT the repo root). Android Studio will run a Gradle sync on first open — wait for "Gradle sync finished."

## 2. Configure secrets

```bash
cd android
cp local.properties.example local.properties
```

Edit `local.properties`:

```properties
sdk.dir=/Users/you/Library/Android/sdk    # Android Studio fills this in automatically; leave it
BACKEND_URL=https://your-fly-app.fly.dev
API_SECRET=<your API_SHARED_SECRET from backend setup>
```

These values get baked into `BuildConfig.BACKEND_URL` and `BuildConfig.API_SECRET` at compile time. They're in the compiled APK but never in source control.

## 3. Build and run

- Plug in your phone with USB debugging on
- In Android Studio's top bar, select your device as the run target
- Click the green ▶ play button
- App installs and launches with a "WorkTick" splash screen

## 4. First launch — grant background permissions

Open the WorkTick app once after installing. It will:

1. Ask for **notification permission** (Android 13+). Tap **Allow** — the notification stays silent and hidden in the shade, but it's required to legalize the foreground ticker service.
2. Ask to **ignore battery optimizations**. Tap **Allow** — on Samsung this is the same as setting Battery → **Unrestricted**.

The home screen of the app shows two status lines (`✓ Background activity allowed`, `✓ Notifications allowed`) so you can confirm both were granted. If you accidentally denied either, tap **Allow background activity** to re-fire the system dialog.

There's one Samsung-specific toggle that has no public API and can't be granted programmatically — see step 6 below.

## 5. Add the widget to your home screen

- Long-press an empty area of your home screen
- Tap **Widgets**
- Find **WorkTick** in the list
- Drag onto a home screen panel

The widget will show its `SYNC` (amber) state briefly, then your real numbers once the WorkManager fetches the schedule (within ~15 minutes, or immediately if you tap the widget).

## 6. Samsung-only: "Never sleeping apps"

For consistent sub-second cent ticking on One UI 6+, also add WorkTick to **Settings → Battery → Background usage limits → Never sleeping apps**. The app's home screen has an **Open app battery settings** button that deep-links you straight to the relevant page — toggle WorkTick on there.

Without this, Doze can still throttle the boundary alarm by a few minutes when a block starts; ticking during an active block is unaffected because foreground services are exempt from Doze.

## How updates work

- **Schedule fetch**: WorkManager runs `ScheduleFetchWorker` every 6 hours, hitting `/schedule` and caching the response in SharedPreferences.
- **Widget tick rate**:
  - Outside an active block: ~30 minute updates (system default).
  - Inside an active block: AlarmManager fires the widget every ~0.5–1 second, but Samsung throttles this aggressively.
  - **Foreground service boost**: when an active block is detected, `WidgetTickerService` starts and pushes 1 Hz updates via `partiallyUpdateAppWidget`. This bypasses Samsung's throttle.
- **Block boundaries**: `BlockBoundaryReceiver` fires at the next block start/end (set via `AlarmManager.setExactAndAllowWhileIdle`), starting/stopping the service.
- **60 fps view**: tap the widget to open `SmoothActivity` for a Choreographer-driven full-screen ticker.

## Lock screen / AOD bonus

Install **Good Lock** from the Galaxy Store, then the **LockStar** module. After enabling, you can drag the WorkTick widget onto your lock screen and AOD — works automatically with the existing widget code, no extra setup.

## Troubleshooting

**Widget shows "Loading..." forever**
- Tap it once to force refresh.
- Check `local.properties` has correct BACKEND_URL and API_SECRET.
- Check backend is reachable: `curl https://your-fly-app.fly.dev/`.

**Widget never updates faster than every 30 minutes**
- Check the foreground service is actually running. Settings → Apps → WorkTick → Notifications → "Widget updater" channel should be enabled.
- Open the WorkTick app and verify both status lines show ✓. If either shows ⚠, tap the corresponding button to re-grant.
- On Samsung, also confirm WorkTick is in **Never sleeping apps** (step 6 above).

**Widget missing from picker after install**
- Force-stop the app, reboot the phone, try again.
- Verify `worktick_money_info.xml` is in `res/xml/`, not `res/layout/`.
