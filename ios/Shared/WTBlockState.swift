// WTBlockState.swift
// Tri-state classification used by today/week list rows. Mirrors the NOW/DONE/QUEUED
// chips on the Android dashboard (MainActivity.blockRow).

import Foundation

public enum WTBlockState: String {
    case queued
    case active
    case done

    public var label: String {
        switch self {
        case .queued: return "QUEUED"
        case .active: return "NOW"
        case .done:   return "DONE"
        }
    }
}

public extension WorkBlock {
    func state(now: Date) -> WTBlockState {
        let nowS = Int(now.timeIntervalSince1970)
        if end <= nowS { return .done }
        if start <= nowS && nowS < end { return .active }
        return .queued
    }
}

public enum WTDay {
    /// Start-of-day in the device's current timezone, returned as unix seconds.
    public static func startOfDay(_ date: Date, calendar: Calendar = .current) -> Int {
        let d = calendar.startOfDay(for: date)
        return Int(d.timeIntervalSince1970)
    }

    /// Returns the Sunday→Saturday week range containing `date`.
    /// Matches Android's week display (LocalDate.with(weekFields.dayOfWeek(), 1)).
    public static func weekRange(containing date: Date, calendar: Calendar = .current) -> (start: Date, end: Date) {
        var cal = calendar
        cal.firstWeekday = 1 // Sunday
        let comps = cal.dateComponents([.yearForWeekOfYear, .weekOfYear], from: date)
        let start = cal.date(from: comps) ?? cal.startOfDay(for: date)
        let end = cal.date(byAdding: .day, value: 7, to: start) ?? start
        return (start, end)
    }

    /// Unique calendar days in the user's timezone touched by `blocks`,
    /// used to bucket the week summary.
    public static func dayBucket(_ block: WorkBlock, calendar: Calendar = .current) -> Date {
        calendar.startOfDay(for: block.startDate)
    }
}
