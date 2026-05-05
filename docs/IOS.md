# iOS Setup

> **Status:** in-progress. The Swift sources are ready but you need a Mac with Xcode 15+ to actually build. Free Apple ID signing works for testing (re-install weekly); paid Apple Developer membership ($99/yr) for permanent install.

## Prerequisites

- macOS with Xcode 15+
- iPhone running iOS 16.1+ (for Live Activity) or iOS 17+ (recommended)
- Apple ID (free is fine for testing; paid for permanent install)
- Backend deployed (see [BACKEND.md](BACKEND.md))

## 1. Configure secrets

```bash
cd ios
cp WorkTickConfig.example.swift WorkTickConfig.swift
```

Edit `WorkTickConfig.swift`:

```swift
enum WorkTickConfig {
    static let backendURL = URL(string: "https://your-fly-app.fly.dev")!
    static let apiSecret = "your-API_SHARED_SECRET-here"
}
```

`WorkTickConfig.swift` is gitignored. The `.example` file is the template.

## 2. Create the Xcode project

1. Xcode → New Project → iOS → App → Next
   - Product Name: `WorkTick`
   - Team: your Apple ID
   - Bundle ID: `dev.surge.worktick`
   - Interface: SwiftUI · Language: Swift
   - Storage: None · Tests: unchecked

2. Delete the auto-generated `ContentView.swift` and `WorkTickApp.swift` (move to Trash).

3. Drag the four files from this repo's `ios/` folder into your Xcode project, in the same group as the deleted files. Check "Copy items if needed."
   - `WorkTickHostApp.swift`
   - `WorkTickWidget.swift`
   - `WorkTickLiveActivity.swift`
   - `WorkTickConfig.swift` (the one you copied/filled in, NOT the .example)

## 3. Capabilities

In the project navigator, click the project root → app target → **Signing & Capabilities** tab:

- Click **+ Capability** → **Background Modes** → check "Background fetch"

In `Info.plist` (right-click → Add Row):

- Key `NSSupportsLiveActivities`, type Boolean, value YES
- Key `BGTaskSchedulerPermittedIdentifiers`, type Array, with one String element: `dev.surge.worktick.refresh`

## 4. Add the Widget Extension target

1. File → New → Target → iOS → **Widget Extension** → Next
   - Product Name: `WorkTickWidgetExtension`
   - Include Live Activity: **✓ checked**
   - Include Configuration App Intent: ☐ unchecked
   - Finish → Activate when prompted

2. Delete the auto-generated widget bundle file. Then drag `WorkTickWidget.swift` and `WorkTickLiveActivity.swift` from your project structure INTO the widget extension group as well. **Check Target Membership: both the app target AND the widget extension.** This is in the right-side File Inspector — make sure both checkboxes are ticked for these files.

3. Do the same for `WorkTickConfig.swift` — must be a member of BOTH targets.

4. Create a new Swift file in the widget extension group called `WorkTickWidgetBundle.swift`:

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

## 5. Run

1. Connect iPhone via USB (or use a paired wireless connection)
2. Top toolbar: change run target from simulator to your device
3. Run the app target. First run will require trusting the developer profile on the phone (Settings → General → VPN & Device Management → trust your Apple ID)
4. App opens to a minimal "WorkTick" screen with current dollars

## 6. Add the home screen widget

- Long-press home screen → + button (top-left) → search WorkTick → Small or Medium → Add Widget

## 7. Enable Live Activities

- Settings → WorkTick → **Live Activities** → enable
- Settings → Face ID & Passcode → "Allow Access When Locked" → **Live Activities** on

When a work block becomes active, opening the app starts a Live Activity automatically. It appears on lock screen and in Dynamic Island, ticking up at 1 Hz.

## How updates work on iOS

- **Home screen widget**: Apple does NOT permit 1 Hz custom-content updates. The widget refreshes via timeline at most every few minutes during active blocks. The elapsed-time line below the dollar figure uses `Text(timerInterval:)` which Apple specifically supports for 1 Hz time displays — that part ticks smoothly.
- **Live Activity** (lock screen + Dynamic Island): supports 1 Hz updates via `TimelineView(.periodic(by: ...))`. Tick rate scales with hourly rate (twice per penny). At $30/hr that's 0.6s ticks; at $60/hr it's 0.3s ticks.
- **Tap the widget** → opens `SmoothActivity` equivalent for full-screen smooth view (not yet implemented; see TODO).

## Troubleshooting

**"Cannot find type 'WorkTickConfig' in scope"**
- You haven't created `WorkTickConfig.swift` yet, OR it's not added to the right target. Check File Inspector → Target Membership.

**Live Activity never appears**
- Confirm a block is currently active (`start ≤ now < end`).
- Open the app while in the block; the Live Activity is started by `WTManager.reconcileLiveActivity`.
- Check Settings → WorkTick → Live Activities is on.

**Widget shows "Loading..."**
- `WT.backendURL` or `WT.apiSecret` is wrong. Check `WorkTickConfig.swift`.
- Backend is unreachable. Test from terminal: `curl -H "Authorization: Bearer $SECRET" https://your-fly-app.fly.dev/schedule`.

**Free signing expired (after 7 days)**
- Reconnect phone, click Run in Xcode again. App reinstalls with a fresh week.

## TODO

- [ ] Build a `SmoothActivity` equivalent on iOS (full-screen 60 fps view that opens when widget is tapped)
- [ ] Add `BackgroundTasks` proper registration in `Info.plist` for `BGAppRefreshTaskRequest`
- [ ] Test cross-target sharing of `WorkTickConfig` types — may need to extract API model + math into a shared Swift package
