// WTBackgroundScheduler.swift
// Wires up BGTaskScheduler so the schedule re-polls and the Live Activity
// reconciles even when the app isn't open. Single task identifier:
//
//   • dev.surge.worktick.refresh    — BGAppRefreshTask. earliestBeginDate is
//                                     set to either the next block boundary
//                                     (+30s) or 6 h out, whichever is sooner.
//
// iOS doesn't honor exact-second wake-up requests; we hand the system the
// earliest desired fire time and let it schedule us within tens of seconds
// to a minute. The Live Activity's TimelineView smooths over the jitter.
//
// The host app's @main scene registers the handler via the
// `.backgroundTask(.appRefresh(...))` modifier in App/WorkTickHostApp.swift.

import Foundation
import BackgroundTasks

public enum WTBackgroundIdentifier {
    public static let refresh = "dev.surge.worktick.refresh"
}

public final class WTBackgroundScheduler {
    public static let shared = WTBackgroundScheduler()

    /// Submit a refresh request based on the current cached schedule. Safe to
    /// call from any thread; no-op if the system rejects the submission
    /// (already scheduled, throttled, simulator quirk, etc.).
    public func scheduleAll(now: Date = Date()) {
        scheduleRefresh(schedule: WTScheduleStore.shared.read(), now: now)
    }

    public func scheduleRefresh(schedule: Schedule?, now: Date = Date()) {
        let req = BGAppRefreshTaskRequest(identifier: WTBackgroundIdentifier.refresh)
        req.earliestBeginDate = nextRefreshDate(schedule: schedule, now: now)
        try? BGTaskScheduler.shared.submit(req)
    }

    /// Cancel everything (used on sign-out / settings reset).
    public func cancelAll() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: WTBackgroundIdentifier.refresh)
    }

    private func nextRefreshDate(schedule: Schedule?, now: Date) -> Date {
        // Fallback: 6 h cadence, mirroring Android ScheduleFetchWorker.
        let cap = now.addingTimeInterval(6 * 3600)
        guard let schedule else { return cap }
        if let boundary = WTMath.nextBoundary(blocks: schedule.blocks, now: now) {
            return min(cap, boundary.addingTimeInterval(30))
        }
        return cap
    }
}
