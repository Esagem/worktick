// WTEventKitPoller.swift
// EventKit-backed schedule poller. Replaces the Android GoogleCalendarClient +
// SchedulePoller pair: walks the user's iOS calendars, finds events matching
// the configured title, dedupes/sorts, and produces a Schedule.
//
// Authorization model:
//   • iOS 17+: requestFullAccessToEvents() — required for past+future event reads.
//   • iOS 16:  requestAccess(to: .event) — the legacy single-permission API.
// The Info.plist must include both NSCalendarsFullAccessUsageDescription (17+)
// and NSCalendarsUsageDescription (legacy fallback).

import Foundation
import EventKit

public enum WTPollError: LocalizedError {
    case notAuthorized
    case eventKitFailure(String)

    public var errorDescription: String? {
        switch self {
        case .notAuthorized: return "Calendar access not granted"
        case .eventKitFailure(let s): return s
        }
    }
}

public enum WTAuthStatus {
    case notDetermined
    case denied
    case restricted
    case fullAccess
    case writeOnly  // iOS 17+ — useless for our read use case

    public var isUsable: Bool {
        if case .fullAccess = self { return true }
        return false
    }
}

public final class WTEventKitPoller {
    public static let shared = WTEventKitPoller()

    private let store = EKEventStore()
    private let settings: WTSettings

    /// Lookback/lookahead window. Matches Android SchedulePoller (365d back, 14d ahead).
    /// Backwards range fuels the "all-time" totals card.
    public static let lookbackDays: Int = 365
    public static let lookaheadDays: Int = 14

    public init(settings: WTSettings = .shared) {
        self.settings = settings
    }

    // MARK: Authorization

    public func authorizationStatus() -> WTAuthStatus {
        let raw = EKEventStore.authorizationStatus(for: .event)
        if #available(iOS 17.0, *) {
            switch raw {
            case .notDetermined: return .notDetermined
            case .denied:        return .denied
            case .restricted:    return .restricted
            case .writeOnly:     return .writeOnly
            case .fullAccess:    return .fullAccess
            case .authorized:    return .fullAccess  // legacy mapping
            @unknown default:    return .denied
            }
        } else {
            switch raw {
            case .notDetermined: return .notDetermined
            case .denied:        return .denied
            case .restricted:    return .restricted
            case .authorized:    return .fullAccess
            @unknown default:    return .denied
            }
        }
    }

    /// Triggers the system permission prompt if needed. Returns true if the
    /// app ends up with full read access.
    public func requestAccess() async -> Bool {
        if #available(iOS 17.0, *) {
            do {
                let granted = try await store.requestFullAccessToEvents()
                settings.eventKitAuthorized = granted
                return granted
            } catch {
                settings.eventKitAuthorized = false
                return false
            }
        } else {
            return await withCheckedContinuation { cont in
                store.requestAccess(to: .event) { granted, _ in
                    self.settings.eventKitAuthorized = granted
                    cont.resume(returning: granted)
                }
            }
        }
    }

    // MARK: Calendar enumeration

    public func availableCalendars() -> [EKCalendar] {
        store.calendars(for: .event).sorted { $0.title.lowercased() < $1.title.lowercased() }
    }

    // MARK: Polling

    public func pollOnce(now: Date = Date()) async throws -> Schedule {
        guard authorizationStatus().isUsable else {
            throw WTPollError.notAuthorized
        }

        let target = settings.eventTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        let targetLower = target.lowercased()
        let rate = settings.hourlyRate

        let cal = Calendar.current
        let from = cal.date(byAdding: .day, value: -Self.lookbackDays, to: now) ?? now
        let to   = cal.date(byAdding: .day, value:  Self.lookaheadDays, to: now) ?? now

        // Calendar selection: configured subset, or all if none chosen.
        let allCalendars = store.calendars(for: .event)
        let selectedIds = Set(settings.selectedCalendarIDs)
        let calendars = selectedIds.isEmpty
            ? allCalendars
            : allCalendars.filter { selectedIds.contains($0.calendarIdentifier) }

        guard !calendars.isEmpty else {
            // No calendars available — record empty schedule (mirrors Android's
            // "0 calendars" path which still writes an empty Schedule).
            let empty = Schedule(
                fetchedAt: Int(now.timeIntervalSince1970),
                hourlyRate: rate,
                blocks: [],
                workEventTitle: target
            )
            settings.lastPollAt = now
            settings.lastPollError = nil
            try WTScheduleStore.shared.write(empty)
            return empty
        }

        // EventKit caps a single predicate at ~4 years. Our window is 379 days
        // so a single predicate is fine.
        let predicate = store.predicateForEvents(withStart: from, end: to, calendars: calendars)
        let events = store.events(matching: predicate)

        var matched: [WorkBlock] = []
        for ev in events {
            // Skip all-day events to mirror Android (which only accepts dateTime events).
            if ev.isAllDay { continue }
            let title = (ev.title ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard title == targetLower else { continue }
            guard let start = ev.startDate, let end = ev.endDate, end > start else { continue }
            matched.append(WorkBlock(
                start: Int(start.timeIntervalSince1970),
                end:   Int(end.timeIntervalSince1970)
            ))
        }

        // Dedupe by start (recurrences across shared calendars), then sort.
        var seen = Set<Int>()
        let unique = matched
            .filter { seen.insert($0.start).inserted }
            .sorted { $0.start < $1.start }

        let schedule = Schedule(
            fetchedAt: Int(now.timeIntervalSince1970),
            hourlyRate: rate,
            blocks: unique,
            workEventTitle: target
        )

        settings.lastPollAt = now
        settings.lastPollError = nil
        try WTScheduleStore.shared.write(schedule)
        return schedule
    }

    /// Convenience used by the UI: poll, persist error, return Result.
    public func pollAndCapture(now: Date = Date()) async -> Result<Schedule, WTPollError> {
        do {
            let s = try await pollOnce(now: now)
            return .success(s)
        } catch let e as WTPollError {
            settings.lastPollError = e.errorDescription
            return .failure(e)
        } catch {
            let wrapped = WTPollError.eventKitFailure(error.localizedDescription)
            settings.lastPollError = wrapped.errorDescription
            return .failure(wrapped)
        }
    }
}
