// WTClockReminderScheduler.swift
// Schedules local notifications at every upcoming block start / end so the
// user gets pinged when it's time to clock in or out. Tapping a notification
// opens the configured portal URL via the shared UNUserNotificationCenter
// delegate.
//
// Why local notifications (not Live Activities or push):
//   • No server. Mirrors the rest of WorkTick — calendar-driven, on-device.
//   • UNCalendarNotificationTrigger / UNTimeIntervalNotificationTrigger fires
//     even when the app is suspended, which is what we need at 6 AM Monday.
//   • iOS caps pending local notifications at 64 per app. We schedule the
//     next 30 boundaries (≈ 15 blocks ahead) which is comfortably under
//     the cap.
//
// Required Info.plist key: none for local notifications. The app must call
// requestAuthorization(options: [.alert, .sound]) once. We do that the first
// time the user sets a portal URL or lead time, then again as a no-op on
// every reschedule (already-granted requests return immediately).

import Foundation
import UIKit
import UserNotifications

public final class WTClockReminderScheduler: NSObject {
    public static let shared = WTClockReminderScheduler()

    private let center = UNUserNotificationCenter.current()
    private let categoryID = "wt.clock_reminder"
    private let identifierPrefix = "wt.clock."

    /// Cap on scheduled boundaries. iOS caps at 64 pending, but each block has
    /// up to 2 (start, end), and we want headroom for any other future
    /// notifications we may add.
    private let maxScheduledBoundaries = 30

    // MARK: Public API

    /// Wire up the tap handler. Call once at app launch.
    public func installDelegate() {
        center.delegate = WTNotificationTapDelegate.shared
        registerCategory()
    }

    /// Request notification permission (alert + sound). Safe to call repeatedly.
    public func requestAuthorization() async -> Bool {
        do {
            return try await center.requestAuthorization(options: [.alert, .sound, .badge])
        } catch {
            return false
        }
    }

    /// Re-build the pending notification set from the current cached schedule
    /// and settings. Call after every poll, after settings edits, and at app
    /// launch. Idempotent — clears and re-schedules the lot.
    public func rescheduleAll(schedule: Schedule?, settings: WTSettings = .shared) {
        Task { await rescheduleAllAsync(schedule: schedule, settings: settings) }
    }

    public func rescheduleAllAsync(schedule: Schedule?, settings: WTSettings = .shared) async {
        // Always wipe our own pending notifications. Done by identifier prefix
        // so we don't stomp anything else the app might schedule later.
        let pending = await center.pendingNotificationRequests()
        let ourIDs = pending.map(\.identifier).filter { $0.hasPrefix(identifierPrefix) }
        if !ourIDs.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: ourIDs)
        }

        guard let schedule, !schedule.blocks.isEmpty else { return }

        // Ensure we're authorized (no-op if user already granted; silent if denied).
        let auth = await center.notificationSettings().authorizationStatus
        guard auth == .authorized || auth == .provisional || auth == .ephemeral else {
            // Don't auto-prompt here — the prompt is shown from Settings when the
            // user first configures the portal URL / lead time.
            return
        }

        let leadSeconds = TimeInterval(settings.notifyLeadMinutes * 60)
        let portalURL = settings.portalURL
        let now = Date()

        // Build a flat list of (fireDate, kind, blockIndex) tuples for every
        // upcoming boundary, sorted, capped, then submitted.
        struct Slot { let fireDate: Date; let kind: Kind; let blockIndex: Int }
        enum Kind: String { case clockIn = "in", clockOut = "out" }

        var slots: [Slot] = []
        for (i, b) in schedule.blocks.enumerated() {
            let inFire = b.startDate.addingTimeInterval(-leadSeconds)
            let outFire = b.endDate.addingTimeInterval(-leadSeconds)
            if inFire > now { slots.append(Slot(fireDate: inFire, kind: .clockIn, blockIndex: i)) }
            if outFire > now { slots.append(Slot(fireDate: outFire, kind: .clockOut, blockIndex: i)) }
        }
        slots.sort { $0.fireDate < $1.fireDate }
        slots = Array(slots.prefix(maxScheduledBoundaries))

        for slot in slots {
            let content = UNMutableNotificationContent()
            content.title = (slot.kind == .clockIn) ? "Time to clock in" : "Time to clock out"
            content.body = portalURL.isEmpty
                ? "Open WorkTick to set your portal URL."
                : (slot.kind == .clockIn
                    ? "Tap to open your portal and clock in."
                    : "Tap to open your portal and clock out.")
            content.sound = .default
            content.categoryIdentifier = categoryID
            content.userInfo = [
                "portal_url": portalURL,
                "kind": slot.kind.rawValue,
            ]

            // UNTimeIntervalNotificationTrigger needs > 0. Floor to 1s for
            // edge cases where a boundary is in the very near future.
            let interval = max(1, slot.fireDate.timeIntervalSince(now))
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
            let id = "\(identifierPrefix)\(slot.blockIndex).\(slot.kind.rawValue)"
            let req = UNNotificationRequest(identifier: id, content: content, trigger: trigger)
            do { try await center.add(req) } catch {
                // Silently skip; nothing else to do.
            }
        }
    }

    public func cancelAll() {
        Task {
            let pending = await center.pendingNotificationRequests()
            let ourIDs = pending.map(\.identifier).filter { $0.hasPrefix(identifierPrefix) }
            center.removePendingNotificationRequests(withIdentifiers: ourIDs)
        }
    }

    // MARK: Private

    private func registerCategory() {
        // No custom actions — tapping the body opens the URL. Categories must
        // still be registered for the delegate flow to attribute the tap.
        let category = UNNotificationCategory(
            identifier: categoryID,
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        center.setNotificationCategories([category])
    }
}

/// Singleton delegate. Opens the portal URL from `userInfo` when the user taps
/// a clock-reminder notification.
final class WTNotificationTapDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = WTNotificationTapDelegate()

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Show even when the app is foregrounded — the user explicitly asked
        // to be pinged at clock-in/out.
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        defer { completionHandler() }
        let info = response.notification.request.content.userInfo
        guard let urlString = info["portal_url"] as? String,
              !urlString.isEmpty,
              let url = URL(string: urlString),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            return
        }
        Task { @MainActor in
            UIApplication.shared.open(url)
        }
    }
}
