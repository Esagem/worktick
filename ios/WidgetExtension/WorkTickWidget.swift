// WorkTickWidget.swift
// Home screen widget. Reads the cached Schedule from the shared App Group
// container (no network — the host app re-polls EventKit on a schedule and
// writes the result there).
//
// REALITY CHECK on iOS widget tick rate:
// Apple does not guarantee 1 Hz updates for arbitrary widget content. The ONE
// thing the OS animates at 1 Hz on home-screen widgets is `Text(timerInterval:)`
// which only displays a time string. To get every-penny updates we use a
// Live Activity instead during active blocks (see WorkTickLiveActivity.swift).

import WidgetKit
import SwiftUI

// MARK: - Timeline entry

struct WTEntry: TimelineEntry {
    let date: Date
    let schedule: Schedule?
    let unauthorized: Bool      // EventKit access not granted yet
}

// MARK: - Provider

struct WTProvider: TimelineProvider {
    func placeholder(in context: Context) -> WTEntry {
        WTEntry(date: Date(), schedule: nil, unauthorized: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (WTEntry) -> Void) {
        let s = WTScheduleStore.shared.read()
        completion(WTEntry(date: Date(), schedule: s, unauthorized: s == nil))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WTEntry>) -> Void) {
        let now = Date()
        guard let schedule = WTScheduleStore.shared.read() else {
            // No cache yet — show the "tap to set up" state and ask the OS to
            // try again in 15 min.
            let entry = WTEntry(date: now, schedule: nil, unauthorized: true)
            completion(Timeline(entries: [entry], policy: .after(now.addingTimeInterval(15 * 60))))
            return
        }

        let isActive = WTMath.allTime(blocks: schedule.blocks, now: now).activeStart != nil
        // ~30 entries spaced every 2 min while active; sparse otherwise. The
        // entries don't tick the money themselves — that's done by TimelineView
        // inside the body view — they just give WidgetKit recompute points so
        // the active/inactive chrome flips at boundaries.
        let interval: TimeInterval = isActive ? 120 : 1800
        let count = isActive ? 30 : 12
        var entries: [WTEntry] = []
        for i in 0..<count {
            let d = now.addingTimeInterval(Double(i) * interval)
            entries.append(WTEntry(date: d, schedule: schedule, unauthorized: false))
        }

        // Refresh policy: just past the next boundary, capped at 6h.
        let cap = now.addingTimeInterval(6 * 3600)
        var nextRefresh = cap
        if let boundary = WTMath.nextBoundary(blocks: schedule.blocks, now: now) {
            nextRefresh = min(nextRefresh, boundary.addingTimeInterval(60))
        }
        completion(Timeline(entries: entries, policy: .after(nextRefresh)))
    }
}

// MARK: - View

struct WTBodyView: View {
    let entry: WTEntry
    let bigSize: CGFloat
    let smallSize: CGFloat

    var body: some View {
        if entry.unauthorized || entry.schedule == nil {
            UnauthorizedView()
        } else if let s = entry.schedule {
            let computed = WTMath.allTime(blocks: s.blocks, now: entry.date)
            let active = computed.activeStart != nil
            let nowS = Int(entry.date.timeIntervalSince1970)
            let dollars = computed.totalDollars(now: nowS, hourlyRate: s.hourlyRate)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(active ? Color.green : Color.secondary.opacity(0.5))
                        .frame(width: 7, height: 7)
                    Text(active ? "Working · All-time gross" : "Off · All-time gross")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                Text(WTFormat.money(dollars))
                    .font(.system(size: bigSize, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                if active, let activeStart = computed.activeStart {
                    HStack(spacing: 4) {
                        Image(systemName: "clock.fill")
                            .font(.system(size: smallSize - 2))
                            .foregroundStyle(.green)
                        Text(timerInterval: Date(timeIntervalSince1970: TimeInterval(activeStart))...Date.distantFuture,
                             countsDown: false)
                            .font(.system(size: smallSize, weight: .medium))
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                        Text("· \(WTFormat.rateText(s.hourlyRate))")
                            .font(.system(size: smallSize))
                            .foregroundStyle(.secondary)
                    }
                } else {
                    let totalHours = Double(computed.totalSeconds(now: nowS)) / 3600.0
                    Text(String(format: "%.1fh @ %@", totalHours, WTFormat.rateText(s.hourlyRate)))
                        .font(.system(size: smallSize))
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }
        }
    }
}

private struct UnauthorizedView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("WorkTick").font(.caption2).foregroundStyle(.secondary)
            Text("Tap to set up")
                .font(.caption.bold())
                .foregroundStyle(.primary)
            Text("Open the app and grant Calendar access.")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(3)
        }
    }
}

struct WTSmallView: View {
    let entry: WTEntry
    var body: some View {
        WTBodyView(entry: entry, bigSize: 22, smallSize: 9).padding()
    }
}

struct WTMediumView: View {
    let entry: WTEntry
    var body: some View {
        WTBodyView(entry: entry, bigSize: 38, smallSize: 12).padding()
    }
}

// MARK: - Widget entry point

struct WorkTickWidget: Widget {
    let kind = "WorkTickWidget"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WTProvider()) { entry in
            WTWidgetView(entry: entry)
        }
        .configurationDisplayName("WorkTick")
        .description("Watch every penny accrue while you're on the clock.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct WTWidgetView: View {
    let entry: WTEntry
    @Environment(\.widgetFamily) var family
    var body: some View {
        Group {
            switch family {
            case .systemSmall: WTSmallView(entry: entry)
            default:           WTMediumView(entry: entry)
            }
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }
}
