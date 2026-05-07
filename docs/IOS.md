# iOS Setup

> **Status:** in-progress. The Swift sources are ready but you need a Mac with Xcode 15+ to actually build. Free Apple ID signing works for testing (re-install weekly); paid Apple Developer membership ($99/yr) for permanent install.

WorkTick on iOS reads work events directly from your **iOS Calendar** via EventKit — no backend, no Google sign-in. To use it with a Google Calendar, subscribe that calendar to iOS first (Settings → Calendar → Accounts → Google).

## Prerequisites

- macOS with Xcode 15+
- iPhone running iOS 16.1+ (for Live Activity) or iOS 17+ (recommended; full-access EventKit API)
- Apple ID (free is fine for testing; paid for permanent install)
- A calendar in iOS Calendar.app that contains your work events (Google subscribers, iCloud, Exchange — anything EventKit can see)

## Source layout

```
ios/
├── Shared/                         (pure-logic; reused by app + widget extension)
│   ├── WTSchedule.swift
│   ├── WTMath.swift
│   ├── WTBlockState.swift
│   └── WTFormat.swift
├── Platform/                       (host-app-only platform glue)
│   ├── WTSettings.swift
│   ├── WTScheduleStore.swift
│   ├── WTEventKitPoller.swift
│   ├── WTBackgroundScheduler.swift
│   └── WTLiveActivityController.swift
├── App/                            (host app target)
│   ├── WorkTickHostApp.swift       (@main)
│   ├── WTAppModel.swift
│   ├── DashboardView.swift
│   ├── SettingsView.swift
│   └── SmoothTickerView.swift
├── WidgetExtension/                (widget extension target)
│   ├── WorkTickWidget.swift
│   └── WorkTickLiveActivity.swift
└── WorkTickConfig.example.swift    (optional debug overrides; gitignored copy)
```

The `Shared/` files are added to **both** targets. `Platform/` and `App/` go in the host app target only. `WidgetExtension/` goes in the widget extension target only.

## 1. Optional: copy the config example

```bash
cd ios
cp WorkTickConfig.example.swift WorkTickConfig.swift
```

The config file is now optional — it only contains debug overrides (e.g. force-active-block testing). The app runs fine without it.

## 2. Create the Xcode project

1. Xcode → New Project → iOS → App → Next
   - Product Name: `WorkTick`
   - Team: your Apple ID
   - Bundle ID: `dev.surge.worktick`
   - Interface: SwiftUI · Language: Swift
   - Storage: None · Tests: unchecked

2. Delete the auto-generated `ContentView.swift` and `WorkTickApp.swift` (move to Trash).

3. In Finder, drag the four folders from `ios/` (`Shared`, `Platform`, `App`, plus `WorkTickConfig.swift` if you made one) into the project navigator. Choose "Create folder references" or "Create groups" (groups is fine). Make sure the host app target checkbox is ticked.

## 3. Capabilities (host app target)

Project navigator → app target → **Signing & Capabilities**:

- **+ Capability → App Groups** → add `group.dev.surge.worktick`
- **+ Capability → Background Modes** → check **Background fetch**

In `Info.plist` (right-click → Add Row):

| Key | Type | Value |
|---|---|---|
| `NSSupportsLiveActivities` | Boolean | `YES` |
| `NSCalendarsFullAccessUsageDescription` | String | `WorkTick reads work events from your calendar to compute live earnings.` |
| `NSCalendarsUsageDescription` | String | `WorkTick reads work events from your calendar to compute live earnings.` |
| `BGTaskSchedulerPermittedIdentifiers` | Array of String | `dev.surge.worktick.refresh` |

The two `NSCalendars*` keys are both required: one for iOS 17+ full-access, one for the iOS 16 single-permission API.

## 4. Add the Widget Extension target

1. File → New → Target → iOS → **Widget Extension** → Next
   - Product Name: `WorkTickWidgetExtension`
   - Include Live Activity: **✓ checked**
   - Include Configuration App Intent: ☐ unchecked
   - Finish → Activate when prompted

2. Delete the auto-generated widget bundle file (the one with `@main`). Then drag the `ios/WidgetExtension/` files into this target's group.

3. Add the `Shared/` files to the widget extension target as well. In the project navigator, select each `Shared/*.swift` file and in the right-side File Inspector tick **both** "Target Membership" checkboxes (host app + widget extension).

4. **+ Capability → App Groups** on the widget extension target → add `group.dev.surge.worktick`. (App Groups must be enabled on every target that reads/writes the shared store.)

5. Create a new Swift file in the widget extension group called `WorkTickWidgetBundle.swift`:

```swift
import WidgetKit
import SwiftUI

@main
struct WorkTickWidgetBundle: WidgetBundle {
    var body: some Widget {
        WorkTickWidget()
        WTLiveActivity()
    }
}
```

## 5. Subscribe the work calendar to iOS

If your work events live in Google Calendar, Apple won't let WorkTick read them via OAuth. Instead, subscribe the calendar to iOS:

- iOS Settings → Calendar → Accounts → Add Account → Google → sign in
- Enable the "Calendars" toggle
- Open the Calendar app and confirm your events appear

WorkTick will then see them through EventKit, same as iCloud or Exchange events.

## 6. Run

1. Connect iPhone via USB (or use a paired wireless connection)
2. Top toolbar: change run target from simulator to your device
3. Run the app target. First run will require trusting the developer profile on the phone (Settings → General → VPN & Device Management → trust your Apple ID)
4. App opens to the dashboard. On first launch you'll see the "Calendar access needed" card — tap **Grant access** and approve the system prompt.
5. Open Settings (gear icon) and confirm the **Event title** matches the title used by your work events. Default is `McCrary Summer Work` — change as needed.
6. Tap **Save & refresh now**. The dashboard populates.

## 7. Add the home screen widget

- Long-press home screen → + button (top-left) → search WorkTick → Small or Medium → Add Widget

## 8. Enable Live Activities

- Settings → WorkTick → **Live Activities** → enable
- Settings → Face ID & Passcode → "Allow Access When Locked" → **Live Activities** on

When a work block becomes active, opening the app (or a background refresh) starts a Live Activity automatically. It appears on lock screen and in Dynamic Island, ticking at 1 Hz.

## How updates work on iOS

- **Home screen widget**: WidgetKit doesn't permit arbitrary 1 Hz updates. The widget refreshes via timeline at most every few minutes during active blocks. The elapsed-time row uses `Text(timerInterval:)` which Apple supports for 1 Hz time displays.
- **Live Activity** (lock screen + Dynamic Island): supports sub-second redraws via `TimelineView(.periodic(by: ...))`. Tick rate scales with hourly rate (twice per penny). At $30/hr that's 0.6s ticks; at $60/hr it's 0.3s ticks.
- **Tap the hero card** in the app → opens `SmoothTickerView`, a full-screen 60/120 Hz `TimelineView(.animation)` view.
- **Block boundaries**: iOS doesn't support exact-second wake-up. We submit a `BGAppRefreshTaskRequest` with `earliestBeginDate` set to the next boundary; the OS may defer us by tens of seconds to a minute. The Live Activity's TimelineView smooths over the jitter.

## Troubleshooting

**Dashboard sticks on "Calendar access needed"**
- iOS Settings → WorkTick → Calendars → toggle on. iOS may have downgraded full-access to write-only or denied. Or tap **Open Settings** in the dashboard's permissions card.

**No events show up**
- Open Calendar.app and confirm your work events are visible there.
- In Settings, check the Event title matches the calendar event titles **exactly** (case-insensitive, but otherwise verbatim).
- All-day events are intentionally skipped — events must have a specific start/end time.

**Widget never updates**
- Confirm App Groups capability `group.dev.surge.worktick` is enabled on **both** the app and widget extension targets, with the same identifier on each.
- The widget reads from a shared file in the App Group container; without the group, the host app's writes are invisible to the extension.

**Live Activity never appears**
- Confirm a block is currently active (`start ≤ now < end`) by checking the dashboard.
- Make sure Settings → WorkTick → Live Activities is on.
- Open the app or wait for a background refresh — the controller starts the activity from `WTAppModel.refresh()`.

**Free signing expired (after 7 days)**
- Reconnect phone, click Run in Xcode again. App reinstalls with a fresh week.

## TODO

- [ ] Lock-screen widget complications (separate WidgetKit family)
- [ ] watchOS app target reusing `Shared/`
