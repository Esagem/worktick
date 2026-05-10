// WorkTickWidget.swift
// Home screen widget. Visual mirrors the Android "Terminal" face from
// MoneyTickerWidgetProvider: dark rounded rect, scanline overlay, status dot
// + label top-left, "TOTAL · $RATE/HR" top-right, big right-aligned money
// hero with superscript $ and dim cents tail, bottom-left SHIFT/planned text
// over a progress bar.
//
// Money refresh: each timeline entry wraps the rendered face in
// TimelineView(.periodic(by: tick)) so the OS drives sub-entry redraws as
// fast as it allows on the home screen (typically 1–5 sec on iOS 17+).
// Apple does not permit true 1 Hz numeric ticking on widgets — that lives in
// the optional Live Activity (off by default; see Settings).

import WidgetKit
import SwiftUI

// MARK: - Timeline entry

struct WTEntry: TimelineEntry {
    let date: Date
    let schedule: Schedule?
    let unauthorized: Bool
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
            let entry = WTEntry(date: now, schedule: nil, unauthorized: true)
            completion(Timeline(entries: [entry], policy: .after(now.addingTimeInterval(15 * 60))))
            return
        }

        // Outer entries: dense during active blocks so the system has fresh data
        // points for TimelineView to interpolate between. The inner TimelineView
        // does the per-second redraws within each entry's window.
        let isActive = WTMath.allTime(blocks: schedule.blocks, now: now).activeStart != nil
        let interval: TimeInterval = isActive ? 60 : 1800
        let count = isActive ? 30 : 12
        var entries: [WTEntry] = []
        for i in 0..<count {
            let d = now.addingTimeInterval(Double(i) * interval)
            entries.append(WTEntry(date: d, schedule: schedule, unauthorized: false))
        }

        let cap = now.addingTimeInterval(6 * 3600)
        var nextRefresh = cap
        if let boundary = WTMath.nextBoundary(blocks: schedule.blocks, now: now) {
            nextRefresh = min(nextRefresh, boundary.addingTimeInterval(60))
        }
        completion(Timeline(entries: entries, policy: .after(nextRefresh)))
    }
}

// MARK: - State / palette

enum TerminalState {
    case on
    case off
    case sync

    var accent: Color {
        switch self {
        case .on:   return Color(red:  61/255, green: 255/255, blue: 154/255)  // #3DFF9A
        case .off:  return Color(red: 255/255, green:  77/255, blue:  92/255)  // #FF4D5C
        case .sync: return Color(red: 255/255, green: 178/255, blue:  44/255)  // #FFB22C
        }
    }

    var accentDim: Color {
        switch self {
        case .on:   return Color(red:  26/255, green:  74/255, blue:  48/255)  // #1A4A30
        case .off:  return Color(red:  74/255, green:  24/255, blue:  34/255)  // #4A1822
        case .sync: return Color(red:  90/255, green:  63/255, blue:  10/255)  // #5A3F0A
        }
    }

    var label: String {
        switch self {
        case .on:   return "ON CLOCK"
        case .off:  return "OFF DUTY"
        case .sync: return "SYNC"
        }
    }
}

private enum TerminalColor {
    static let bg     = Color(red:  11/255, green:  13/255, blue:  16/255)  // #0B0D10
    static let white  = Color.white
    static let mid    = Color(red: 138/255, green: 143/255, blue: 151/255)  // #8A8F97
    static let dim    = Color(red: 122/255, green: 128/255, blue: 137/255)  // #7A8089
    static let label  = Color(red: 207/255, green: 211/255, blue: 218/255)  // #CFD3DA
    static let track  = Color(red:  26/255, green:  29/255, blue:  34/255)  // #1A1D22
    static let scan   = Color.white.opacity(0.02)
}

// MARK: - Widget body

struct WTMediumView: View {
    let entry: WTEntry

    var body: some View {
        if entry.unauthorized || entry.schedule == nil {
            UnauthorizedFace()
        } else if let s = entry.schedule {
            // Inner TimelineView re-renders the money figure as fast as the
            // OS permits within this entry's window. Tick interval is the
            // sub-penny target; the system throttles to its own minimum
            // (~1 sec on iOS 17+, more on older / under load).
            TimelineView(.periodic(from: entry.date,
                                   by: WTFormat.tickInterval(hourlyRate: s.hourlyRate))) { ctx in
                TerminalFace(schedule: s, now: ctx.date)
            }
        }
    }
}

private struct TerminalFace: View {
    let schedule: Schedule
    let now: Date

    var body: some View {
        let computed = WTMath.allTime(blocks: schedule.blocks, now: now)
        let active = computed.activeStart != nil
        let state: TerminalState = schedule.blocks.isEmpty ? .sync : (active ? .on : .off)

        let nowS = Int(now.timeIntervalSince1970)
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let dollars = computed.totalDollarsMs(nowMs: nowMs, hourlyRate: schedule.hourlyRate)
        let totalHours = Double(computed.totalSeconds(now: nowS)) / 3600.0
        let todayStart = WTDay.startOfDay(now)
        let shiftHours = Double(computed.currentShiftSeconds(now: nowS, todayStart: todayStart)) / 3600.0
        let plannedHours = max(0.5, schedule.plannedShiftHours)

        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            ZStack {
                // Background + scanlines + border
                TerminalBackground(state: state)

                // Top row: status (left) + total/rate (right)
                VStack {
                    HStack(alignment: .center) {
                        StatusPill(state: state)
                        Spacer()
                        TotalRatePill(state: state, totalHours: totalHours, rate: schedule.hourlyRate)
                    }
                    Spacer()
                }
                .padding(.horizontal, w * 0.05)
                .padding(.top, h * 0.10)

                // Money hero (right-aligned, vertically biased toward center)
                HStack {
                    Spacer()
                    MoneyHero(dollars: dollars, state: state, maxWidth: w * 0.62)
                }
                .padding(.horizontal, w * 0.05)

                // Bottom row: shift label + progress bar
                VStack {
                    Spacer()
                    VStack(alignment: .leading, spacing: 4) {
                        ShiftLabel(shiftHours: shiftHours, plannedHours: plannedHours)
                        ProgressRule(state: state, fraction: min(1.0, max(0.0, shiftHours / plannedHours)))
                    }
                }
                .padding(.horizontal, w * 0.05)
                .padding(.bottom, h * 0.10)
            }
        }
    }
}

// MARK: - Background

private struct TerminalBackground: View {
    let state: TerminalState

    var body: some View {
        ZStack {
            // Solid fill
            RoundedRectangle(cornerRadius: 22)
                .fill(TerminalColor.bg)
            // Faint scanlines (every 6pt)
            Canvas { ctx, size in
                let stripe = Path { p in
                    var y: CGFloat = 0
                    while y < size.height {
                        p.addRect(CGRect(x: 0, y: y, width: size.width, height: 1))
                        y += 6
                    }
                }
                ctx.fill(stripe, with: .color(TerminalColor.scan))
            }
            .clipShape(RoundedRectangle(cornerRadius: 22))
            // Accent border
            RoundedRectangle(cornerRadius: 22)
                .stroke(state.accentDim, lineWidth: 1.5)
        }
    }
}

// MARK: - Status pill

private struct StatusPill: View {
    let state: TerminalState

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(state.accent)
                .frame(width: 10, height: 10)
                .shadow(color: state == .on ? state.accent : .clear, radius: 4)
            Text(state.label)
                .font(.system(size: 11, weight: .heavy, design: .monospaced))
                .tracking(1.6)
                .foregroundStyle(state.accent)
        }
    }
}

// MARK: - Total / rate (top right)

private struct TotalRatePill: View {
    let state: TerminalState
    let totalHours: Double
    let rate: Double

    var body: some View {
        HStack(spacing: 6) {
            Text("TOTAL \(formatHM(totalHours))")
                .font(.system(size: 10, weight: .heavy, design: .monospaced))
                .tracking(1.4)
                .foregroundStyle(TerminalColor.label)
            Text("·")
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(TerminalColor.dim)
            Text("$\(Int(rate.rounded()))/HR")
                .font(.system(size: 10, weight: .heavy, design: .monospaced))
                .tracking(1.4)
                .foregroundStyle(state.accent)
        }
    }
}

// MARK: - Money hero

private struct MoneyHero: View {
    let dollars: Double
    let state: TerminalState
    let maxWidth: CGFloat

    var body: some View {
        // FLOOR rounding so the displayed cent matches the same boundary the
        // ticker schedules to. Default banker's rounding flips the display
        // half a tick early at $30/hr, putting visual updates out of phase.
        let cents = Int((dollars * 100).rounded(.down))
        let whole = cents / 100
        let dec = String(format: ".%02d", abs(cents % 100))

        HStack(alignment: .firstTextBaseline, spacing: 2) {
            Text("$")
                .font(.system(size: 20, weight: .black, design: .monospaced))
                .foregroundStyle(state.accent)
                .baselineOffset(18)
            Text("\(whole)")
                .font(.system(size: 48, weight: .black, design: .monospaced))
                .foregroundStyle(TerminalColor.white)
                .monospacedDigit()
            Text(dec)
                .font(.system(size: 26, weight: .black, design: .monospaced))
                .foregroundStyle(TerminalColor.mid)
                .monospacedDigit()
        }
        .lineLimit(1)
        .frame(maxWidth: maxWidth, alignment: .trailing)
        .minimumScaleFactor(0.4)
    }
}

// MARK: - Shift label + progress rule

private struct ShiftLabel: View {
    let shiftHours: Double
    let plannedHours: Double

    var body: some View {
        HStack(spacing: 4) {
            Text("SHIFT \(formatHM(shiftHours))")
                .font(.system(size: 10, weight: .heavy, design: .monospaced))
                .tracking(1.4)
                .foregroundStyle(TerminalColor.label)
            Text(" / ")
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(TerminalColor.dim)
            Text(formatHM(plannedHours))
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(TerminalColor.dim)
        }
    }
}

private struct ProgressRule: View {
    let state: TerminalState
    let fraction: Double

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let w = geo.size.width
            ZStack(alignment: .leading) {
                // End caps (small vertical ticks)
                HStack {
                    Capsule().fill(state.accentDim).frame(width: 1.5, height: h * 1.6)
                    Spacer()
                    Capsule().fill(state.accentDim).frame(width: 1.5, height: h * 1.6)
                }
                // Track
                Capsule().fill(TerminalColor.track).frame(height: 1.5)
                // Fill
                Capsule()
                    .fill(state.accent)
                    .frame(width: max(0, w * fraction), height: 2)
                    .shadow(color: state == .on ? state.accent.opacity(0.7) : .clear, radius: 3)
            }
        }
        .frame(height: 6)
    }
}

// MARK: - Helpers

private func formatHM(_ hours: Double) -> String {
    let totalMin = max(0, Int((hours * 60).rounded()))
    let h = totalMin / 60
    let m = totalMin % 60
    return String(format: "%d:%02d", h, m)
}

// MARK: - Unauthorized

private struct UnauthorizedFace: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 22).fill(TerminalColor.bg)
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(TerminalState.sync.accent)
                        .frame(width: 8, height: 8)
                    Text("SYNC")
                        .font(.system(size: 10, weight: .heavy, design: .monospaced))
                        .tracking(1.4)
                        .foregroundStyle(TerminalState.sync.accent)
                }
                Spacer()
                Text("Tap to set up")
                    .font(.system(size: 16, weight: .bold, design: .monospaced))
                    .foregroundStyle(.white)
                Text("Open the app and grant Calendar access.")
                    .font(.system(size: 9, design: .monospaced))
                    .foregroundStyle(TerminalColor.dim)
                    .lineLimit(2)
            }
            .padding()
        }
    }
}

// MARK: - Widget entry point

struct WorkTickWidget: Widget {
    let kind = "WorkTickWidget"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WTProvider()) { entry in
            WTMediumView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("WorkTick")
        .description("Watch every penny accrue while you're on the clock.")
        .supportedFamilies([.systemMedium])
    }
}
