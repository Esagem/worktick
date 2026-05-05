// WorkTickWidget.swift
// iOS 17+ WidgetKit + ActivityKit Live Activity.
//
// IMPORTANT REALITY CHECK on iOS widget tick rate:
//
// Apple does not guarantee 1 Hz updates for arbitrary widget content. The ONE thing
// the OS does animate at 1 Hz on home-screen widgets is `Text(timerInterval:)` —
// which only displays a time string (HH:MM:SS), not arbitrary numeric content.
//
// To get "every penny" smoothly, we use TWO renderings:
//   • Home screen widget — money updates at each timeline refresh (~every few minutes
//     during work blocks), with a live-ticking elapsed-time line below it. The money
//     climbs visibly throughout your shift; the time ticks every second.
//   • Live Activity (lock screen + Dynamic Island) — when a work block is active,
//     the host app starts a Live Activity that explicitly supports 1 Hz updates,
//     where every penny truly ticks live. See WorkTickLiveActivity.swift.

import WidgetKit
import SwiftUI

// MARK: - Configuration

enum WT {
    static let backendURL = URL(string: "https://YOUR-APP.fly.dev")!
    static let apiSecret = "PASTE_API_SHARED_SECRET_HERE"
}

// MARK: - API model

struct WorkBlock: Codable, Hashable {
    let start: Int
    let end: Int
}

struct ScheduleResponse: Codable {
    let fetchedAt: Int?
    let timezone: String
    let hourlyRate: Double
    let blocks: [WorkBlock]

    enum CodingKeys: String, CodingKey {
        case fetchedAt = "fetched_at"
        case timezone
        case hourlyRate = "hourly_rate"
        case blocks
    }
}

// MARK: - Networking

struct WTClient {
    static func fetchSchedule() async throws -> ScheduleResponse {
        var req = URLRequest(url: WT.backendURL.appendingPathComponent("schedule"))
        req.timeoutInterval = 10
        if !WT.apiSecret.isEmpty {
            req.setValue("Bearer \(WT.apiSecret)", forHTTPHeaderField: "Authorization")
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(ScheduleResponse.self, from: data)
    }
}

// MARK: - Math (mirror of backend logic)

struct WTComputed {
    let completedSeconds: Int
    let activeBlockStart: Int?  // unix seconds; nil if not currently working

    func totalSeconds(at now: Date) -> Double {
        let nowS = now.timeIntervalSince1970
        if let active = activeBlockStart {
            return Double(completedSeconds) + max(0, nowS - Double(active))
        }
        return Double(completedSeconds)
    }

    func totalDollars(at now: Date, hourlyRate: Double) -> Double {
        return totalSeconds(at: now) * hourlyRate / 3600.0
    }
}

enum WTMath {
    static func allTime(blocks: [WorkBlock], now: Date) -> WTComputed {
        let nowS = Int(now.timeIntervalSince1970)
        var completed = 0
        var active: Int? = nil
        for b in blocks {
            if b.end <= nowS {
                completed += (b.end - b.start)
            } else if b.start <= nowS && nowS < b.end {
                active = b.start
            }
        }
        return WTComputed(completedSeconds: completed, activeBlockStart: active)
    }

    static func currentBlockEnd(blocks: [WorkBlock], now: Date) -> Date? {
        let nowS = Int(now.timeIntervalSince1970)
        for b in blocks where b.start <= nowS && nowS < b.end {
            return Date(timeIntervalSince1970: TimeInterval(b.end))
        }
        return nil
    }

    static func nextBlockStart(blocks: [WorkBlock], now: Date) -> Date? {
        let nowS = Int(now.timeIntervalSince1970)
        let futureStarts = blocks.compactMap { $0.start > nowS ? $0.start : nil }.sorted()
        return futureStarts.first.map { Date(timeIntervalSince1970: TimeInterval($0)) }
    }
}

// MARK: - Timeline entry

struct WTEntry: TimelineEntry {
    let date: Date
    let schedule: ScheduleResponse?
    let error: String?
}

// MARK: - Provider

struct WTProvider: TimelineProvider {
    func placeholder(in context: Context) -> WTEntry {
        WTEntry(date: Date(), schedule: nil, error: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (WTEntry) -> Void) {
        Task {
            do {
                let s = try await WTClient.fetchSchedule()
                completion(WTEntry(date: Date(), schedule: s, error: nil))
            } catch {
                completion(WTEntry(date: Date(), schedule: nil, error: error.localizedDescription))
            }
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WTEntry>) -> Void) {
        Task {
            let now = Date()
            do {
                let schedule = try await WTClient.fetchSchedule()

                // Generate a series of entries for the next ~6 hours so the dollar amount
                // updates frequently on screen even without network. We make ~30 entries,
                // each a few minutes apart while the active block is running.
                var entries: [WTEntry] = []
                let isActive = WTMath.allTime(blocks: schedule.blocks, now: now).activeBlockStart != nil

                let interval: TimeInterval = isActive ? 120 : 1800   // 2 min if working, else 30 min
                let count = isActive ? 60 : 12                        // ~2 hours of pre-baked entries
                for i in 0..<count {
                    let d = now.addingTimeInterval(Double(i) * interval)
                    entries.append(WTEntry(date: d, schedule: schedule, error: nil))
                }

                // Decide refresh policy
                let cap = now.addingTimeInterval(6 * 3600)
                var nextRefresh = cap
                if let blockEnd = WTMath.currentBlockEnd(blocks: schedule.blocks, now: now) {
                    nextRefresh = min(nextRefresh, blockEnd.addingTimeInterval(60))
                } else if let nextStart = WTMath.nextBlockStart(blocks: schedule.blocks, now: now) {
                    nextRefresh = min(nextRefresh, nextStart.addingTimeInterval(5))
                }
                completion(Timeline(entries: entries, policy: .after(nextRefresh)))
            } catch {
                let entry = WTEntry(date: now, schedule: nil, error: error.localizedDescription)
                completion(Timeline(entries: [entry], policy: .after(now.addingTimeInterval(15 * 60))))
            }
        }
    }
}

// MARK: - Formatting

private let moneyFormatter: NumberFormatter = {
    let f = NumberFormatter()
    f.numberStyle = .currency
    f.locale = Locale(identifier: "en_US")
    f.minimumFractionDigits = 2
    f.maximumFractionDigits = 2
    return f
}()

func formatMoney(_ amount: Double) -> String {
    moneyFormatter.string(from: NSNumber(value: amount)) ?? "$0.00"
}

// MARK: - View

struct WTBodyView: View {
    let entry: WTEntry
    let bigSize: CGFloat
    let smallSize: CGFloat

    var body: some View {
        if let s = entry.schedule {
            let computed = WTMath.allTime(blocks: s.blocks, now: entry.date)
            let active = computed.activeBlockStart != nil
            let dollars = computed.totalDollars(at: entry.date, hourlyRate: s.hourlyRate)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(active ? Color.green : Color.secondary.opacity(0.5))
                        .frame(width: 7, height: 7)
                    Text(active ? "Working · All-time gross" : "Off · All-time gross")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                Text(formatMoney(dollars))
                    .font(.system(size: bigSize, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                if active, let activeStart = computed.activeBlockStart {
                    // Live-ticking elapsed time during the current block — this DOES update
                    // every second on screen (Apple's supported timer style).
                    HStack(spacing: 4) {
                        Image(systemName: "clock.fill")
                            .font(.system(size: smallSize - 2))
                            .foregroundStyle(.green)
                        Text(timerInterval: Date(timeIntervalSince1970: TimeInterval(activeStart))...Date.distantFuture,
                             countsDown: false)
                            .font(.system(size: smallSize, weight: .medium))
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                        Text("· \(formatMoney(s.hourlyRate))/h")
                            .font(.system(size: smallSize))
                            .foregroundStyle(.secondary)
                    }
                } else {
                    let totalHours = computed.totalSeconds(at: entry.date) / 3600.0
                    Text(String(format: "%.1fh @ %@/h", totalHours, formatMoney(s.hourlyRate)))
                        .font(.system(size: smallSize))
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }
        } else if let err = entry.error {
            VStack(alignment: .leading, spacing: 4) {
                Text("WorkTick").font(.caption2).foregroundStyle(.secondary)
                Text("⚠︎ \(err)").font(.caption2).foregroundStyle(.red).lineLimit(4)
            }
        } else {
            ProgressView()
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
            default: WTMediumView(entry: entry)
            }
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }
}
