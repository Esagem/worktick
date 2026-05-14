// WTAppModel.swift
// Central ObservableObject the SwiftUI views observe. Wires together poller,
// schedule store, Live Activity controller, and background scheduling so the
// views just call `refresh()` and read state.

import Foundation
import SwiftUI

@MainActor
public final class WTAppModel: ObservableObject {
    public static let shared = WTAppModel()

    @Published public private(set) var schedule: Schedule?
    @Published public private(set) var authStatus: WTAuthStatus
    @Published public private(set) var lastError: String?
    @Published public private(set) var isRefreshing: Bool = false

    private let settings: WTSettings
    private let poller: WTEventKitPoller
    private let store: WTScheduleStore
    private let scheduler: WTBackgroundScheduler

    public init(
        settings: WTSettings = .shared,
        poller: WTEventKitPoller = .shared,
        store: WTScheduleStore = .shared,
        scheduler: WTBackgroundScheduler = .shared
    ) {
        self.settings = settings
        self.poller = poller
        self.store = store
        self.scheduler = scheduler
        self.schedule = store.read()
        self.authStatus = poller.authorizationStatus()
        self.lastError = settings.lastPollError
    }

    public var settingsRef: WTSettings { settings }
    public var pollerRef: WTEventKitPoller { poller }

    /// Called from the SwiftUI .task at app launch and from the "Refresh"
    /// buttons. Re-polls EventKit, updates Live Activity, schedules the next
    /// background fire.
    public func refresh() async {
        isRefreshing = true
        defer { isRefreshing = false }

        // Re-check auth in case user toggled it in iOS Settings while we were backgrounded.
        let status = poller.authorizationStatus()
        self.authStatus = status

        if status == .notDetermined {
            // Don't auto-prompt here — let the Settings/Dashboard views show
            // an explicit "Grant access" button. We can still load whatever
            // is in the cache.
            self.schedule = store.read()
            return
        }

        guard status.isUsable else {
            self.lastError = "Calendar access not granted"
            self.schedule = store.read()
            return
        }

        let result = await poller.pollAndCapture()
        switch result {
        case .success(let s):
            self.schedule = s
            self.lastError = nil
            WTLiveActivityController.shared.reconcile(schedule: s)
            scheduler.scheduleAll()
            await WTClockReminderScheduler.shared.rescheduleAllAsync(schedule: s, settings: settings)
        case .failure(let e):
            self.lastError = e.errorDescription
            self.schedule = store.read()  // fall back to whatever's cached
            await WTClockReminderScheduler.shared.rescheduleAllAsync(schedule: self.schedule, settings: settings)
        }
    }

    /// Used by Settings when the user toggles "Grant calendar access".
    public func requestAccess() async {
        _ = await poller.requestAccess()
        self.authStatus = poller.authorizationStatus()
        if authStatus.isUsable {
            await refresh()
        }
    }

    /// Apply a settings edit (hourly rate or event title) and re-poll so the
    /// dashboard reflects the new values immediately.
    public func updateSettings(hourlyRate: Double? = nil, eventTitle: String? = nil) async {
        if let r = hourlyRate { settings.hourlyRate = r }
        if let t = eventTitle { settings.eventTitle = t }
        await refresh()
    }

    public func setSelectedCalendars(_ ids: [String]) async {
        settings.selectedCalendarIDs = ids
        await refresh()
    }
}
