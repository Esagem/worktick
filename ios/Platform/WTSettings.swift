// WTSettings.swift
// User-configurable settings, persisted to a UserDefaults suite shared with the
// widget extension via App Group `group.dev.surge.worktick`. Mirrors the
// Android WTSettings.kt key set so behavior matches across platforms.
//
// IMPORTANT: For the App Group suite to work, both the host app target AND the
// widget extension target must have the App Groups capability with
// `group.dev.surge.worktick` enabled. See docs/IOS.md.

import Foundation

public enum WTSettingsKey {
    public static let appGroup = "group.dev.surge.worktick"

    public static let hourlyRate          = "hourly_rate"
    public static let eventTitle          = "event_title"
    public static let selectedCalendarIDs = "selected_calendar_ids"
    public static let lastPollAt          = "last_poll_at"          // unix seconds
    public static let lastPollError       = "last_poll_error"
    public static let eventKitAuthorized  = "event_kit_authorized"  // cached
}

public enum WTSettingsDefaults {
    public static let hourlyRate: Double = 30.0
    public static let eventTitle: String = "McCrary Summer Work"
}

public final class WTSettings {
    public static let shared = WTSettings()

    private let store: UserDefaults

    public init(suiteName: String = WTSettingsKey.appGroup) {
        // UserDefaults(suiteName:) returns nil only for the reserved
        // "globalDomain" / app-bundle suite names; the App Group ID is always
        // safe but if it's mis-configured (no entitlement), reads/writes go
        // nowhere silently. We fall back to .standard so the app still works
        // in local dev without an App Group set up.
        self.store = UserDefaults(suiteName: suiteName) ?? .standard
    }

    // MARK: Hourly rate

    public var hourlyRate: Double {
        get {
            let v = store.double(forKey: WTSettingsKey.hourlyRate)
            return v == 0 ? WTSettingsDefaults.hourlyRate : v
        }
        set { store.set(newValue, forKey: WTSettingsKey.hourlyRate) }
    }

    // MARK: Event title

    public var eventTitle: String {
        get { store.string(forKey: WTSettingsKey.eventTitle) ?? WTSettingsDefaults.eventTitle }
        set {
            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            store.set(trimmed.isEmpty ? WTSettingsDefaults.eventTitle : trimmed,
                      forKey: WTSettingsKey.eventTitle)
        }
    }

    // MARK: Selected calendars

    /// Empty array means "use all available calendars" (default behavior on
    /// first launch, matches Android which polls every visible calendar).
    public var selectedCalendarIDs: [String] {
        get { store.stringArray(forKey: WTSettingsKey.selectedCalendarIDs) ?? [] }
        set { store.set(newValue, forKey: WTSettingsKey.selectedCalendarIDs) }
    }

    // MARK: Last poll bookkeeping

    public var lastPollAt: Date? {
        get {
            let v = store.double(forKey: WTSettingsKey.lastPollAt)
            return v == 0 ? nil : Date(timeIntervalSince1970: v)
        }
        set {
            if let d = newValue {
                store.set(d.timeIntervalSince1970, forKey: WTSettingsKey.lastPollAt)
            } else {
                store.removeObject(forKey: WTSettingsKey.lastPollAt)
            }
        }
    }

    public var lastPollError: String? {
        get { store.string(forKey: WTSettingsKey.lastPollError) }
        set {
            if let s = newValue {
                store.set(s, forKey: WTSettingsKey.lastPollError)
            } else {
                store.removeObject(forKey: WTSettingsKey.lastPollError)
            }
        }
    }

    // MARK: EventKit auth cache

    /// Cached auth state. Truth comes from EKEventStore.authorizationStatus
    /// at launch — this is just a hint for widget timeline scheduling.
    public var eventKitAuthorized: Bool {
        get { store.bool(forKey: WTSettingsKey.eventKitAuthorized) }
        set { store.set(newValue, forKey: WTSettingsKey.eventKitAuthorized) }
    }
}
