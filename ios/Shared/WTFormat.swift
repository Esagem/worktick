// WTFormat.swift
// Shared formatters used by host app, widget, and Live Activity. Centralizing
// these matches Android's MainActivity.format* helpers and keeps strings
// identical across surfaces.

import Foundation

public enum WTFormat {
    private static let moneyFormatter: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.locale = Locale(identifier: "en_US")
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        return f
    }()

    public static func money(_ amount: Double) -> String {
        moneyFormatter.string(from: NSNumber(value: amount)) ?? "$0.00"
    }

    /// "5h 23m" / "23m" / "—" — same shape as Android formatHM.
    public static func hoursMinutes(seconds: Int) -> String {
        if seconds <= 0 { return "—" }
        let totalMin = seconds / 60
        let h = totalMin / 60
        let m = totalMin % 60
        if h > 0 { return "\(h)h \(m)m" }
        return "\(m)m"
    }

    public static func hoursMinutes(hours: Double) -> String {
        hoursMinutes(seconds: Int(hours * 3600))
    }

    public static func rateText(_ rate: Double) -> String {
        let trimmed = String(format: "%.2f", rate)
        return "$\(trimmed)/h"
    }

    /// "in 5m" / "in 2h 30m" / "now" / "5m ago" — Android formatRelative.
    public static func relative(seconds: Int) -> String {
        if seconds == 0 { return "now" }
        let abs = Swift.abs(seconds)
        let suffix: String
        let prefix: String
        if seconds > 0 { prefix = "in "; suffix = "" }
        else { prefix = ""; suffix = " ago" }

        let totalMin = abs / 60
        if totalMin < 1 { return "\(prefix)\(abs)s\(suffix)" }
        let h = totalMin / 60
        let m = totalMin % 60
        if h == 0 { return "\(prefix)\(m)m\(suffix)" }
        if m == 0 { return "\(prefix)\(h)h\(suffix)" }
        return "\(prefix)\(h)h \(m)m\(suffix)"
    }

    /// Day label for week rows: "Sun 03" — matches Android weekDayRow.
    public static func dayLabel(_ date: Date, calendar: Calendar = .current) -> String {
        let f = DateFormatter()
        f.calendar = calendar
        f.locale = Locale.autoupdatingCurrent
        f.dateFormat = "EEE dd"
        return f.string(from: date)
    }

    /// "1:30 PM" / "13:30" depending on user locale.
    public static func clockTime(_ date: Date) -> String {
        let f = DateFormatter()
        f.timeStyle = .short
        f.dateStyle = .none
        return f.string(from: date)
    }

    /// "Today 1:30 PM" / "Tue 1:30 PM" — for next-shift countdown.
    public static func dayPlusTime(_ date: Date, calendar: Calendar = .current) -> String {
        let f = DateFormatter()
        f.calendar = calendar
        f.doesRelativeDateFormatting = true
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: date)
    }

    /// Tick interval (seconds) for sub-penny smoothness — twice per penny.
    /// At rate r, seconds-per-penny = 36/r, so tick ≈ 18/r. Clamped to [0.25, 1.0]
    /// to avoid burning battery at low rates or missing pennies at high ones.
    public static func tickInterval(hourlyRate: Double) -> Double {
        guard hourlyRate > 0 else { return 1.0 }
        let secondsPerPenny = 36.0 / hourlyRate
        let ideal = secondsPerPenny / 2.0
        return min(1.0, max(0.25, ideal))
    }
}
