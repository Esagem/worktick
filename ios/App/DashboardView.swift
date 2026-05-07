// DashboardView.swift
// Mirrors the seven sections of the Android MainActivity dashboard:
//   1. Hero (status, money, progress bar) — tap → SmoothTickerView
//   2. Today
//   3. This week
//   4. Next shift
//   5. All-time
//   6. Permissions
//   7. Last poll footer

import SwiftUI
import UIKit

struct DashboardView: View {
    @EnvironmentObject var model: WTAppModel
    @State private var showSmoothTicker = false
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    if !model.authStatus.isUsable {
                        PermissionGateCard()
                    } else if let s = model.schedule {
                        HeroCard(schedule: s)
                            .onTapGesture { showSmoothTicker = true }
                        TodaySection(schedule: s)
                        WeekSection(schedule: s)
                        NextShiftSection(schedule: s)
                        AllTimeSection(schedule: s)
                    } else if model.isRefreshing {
                        ProgressView().padding()
                    } else {
                        EmptyScheduleCard()
                    }

                    PermissionsRow()
                    FooterRow()
                }
                .padding()
            }
            .navigationTitle("WorkTick")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        Task { await model.refresh() }
                    } label: {
                        if model.isRefreshing {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                    .disabled(model.isRefreshing)
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView().environmentObject(model)
            }
            .fullScreenCover(isPresented: $showSmoothTicker) {
                SmoothTickerView().environmentObject(model)
            }
        }
    }
}

// MARK: - Hero

private struct HeroCard: View {
    let schedule: Schedule

    var body: some View {
        TimelineView(.periodic(from: Date(), by: WTFormat.tickInterval(hourlyRate: schedule.hourlyRate))) { ctx in
            let computed = WTMath.allTime(blocks: schedule.blocks, now: ctx.date)
            let active = computed.activeStart != nil
            let nowMs = Int64(ctx.date.timeIntervalSince1970 * 1000)
            let dollars = computed.totalDollarsMs(nowMs: nowMs, hourlyRate: schedule.hourlyRate)

            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Circle()
                        .fill(active ? Color.green : Color.secondary)
                        .frame(width: 10, height: 10)
                    Text(active ? "On the clock" : "Off the clock")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    Spacer()
                    if active, let start = computed.activeStart {
                        Text(timerInterval: Date(timeIntervalSince1970: TimeInterval(start))...Date.distantFuture, countsDown: false)
                            .font(.callout.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                Text(WTFormat.money(dollars))
                    .font(.system(size: 56, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                ProgressBar(schedule: schedule, computed: computed, now: ctx.date)
                Text("All-time gross · \(WTFormat.rateText(schedule.hourlyRate))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(active ? Color.green.opacity(0.12) : Color.secondary.opacity(0.08))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(active ? Color.green : Color.secondary.opacity(0.3), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }
}

private struct ProgressBar: View {
    let schedule: Schedule
    let computed: WTComputed
    let now: Date

    var body: some View {
        let todayStart = WTDay.startOfDay(now)
        let nowS = Int(now.timeIntervalSince1970)
        let shiftSec = computed.currentShiftSeconds(now: nowS, todayStart: todayStart)
        let target = max(1, Int(schedule.plannedShiftHours * 3600))
        let frac = min(1.0, max(0.0, Double(shiftSec) / Double(target)))
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(Color.secondary.opacity(0.2))
                RoundedRectangle(cornerRadius: 4)
                    .fill(computed.activeStart != nil ? Color.green : Color.secondary)
                    .frame(width: geo.size.width * frac)
            }
        }
        .frame(height: 6)
    }
}

// MARK: - Today

private struct TodaySection: View {
    let schedule: Schedule

    var body: some View {
        let now = Date()
        let cal = Calendar.current
        let dayStart = cal.startOfDay(for: now)
        let dayEnd = cal.date(byAdding: .day, value: 1, to: dayStart) ?? dayStart
        let todays = schedule.blocks.filter { b in
            b.startDate >= dayStart && b.startDate < dayEnd
        }

        SectionCard(title: "TODAY") {
            if todays.isEmpty {
                DimRow("No shifts today.")
            } else {
                ForEach(todays) { b in
                    BlockRow(block: b, rate: schedule.hourlyRate, now: now)
                }
                let total = todays.reduce(0) { $0 + $1.durationSeconds }
                Divider().padding(.vertical, 2)
                TotalRow(label: "Today total", seconds: total, rate: schedule.hourlyRate)
            }
        }
    }
}

// MARK: - Week

private struct WeekSection: View {
    let schedule: Schedule

    var body: some View {
        let now = Date()
        let cal = Calendar.current
        let week = WTDay.weekRange(containing: now, calendar: cal)
        let weekBlocks = schedule.blocks.filter { $0.startDate >= week.start && $0.startDate < week.end }

        let days: [Date] = (0..<7).compactMap { cal.date(byAdding: .day, value: $0, to: week.start) }
        let perDay: [(Date, Int)] = days.map { day in
            let dayEnd = cal.date(byAdding: .day, value: 1, to: day) ?? day
            let secs = weekBlocks
                .filter { $0.startDate >= day && $0.startDate < dayEnd }
                .reduce(0) { $0 + $1.durationSeconds }
            return (day, secs)
        }
        let weekTotal = perDay.reduce(0) { $0 + $1.1 }
        let maxSec = max(1, perDay.map(\.1).max() ?? 1)

        SectionCard(title: "THIS WEEK") {
            ForEach(perDay, id: \.0) { day, secs in
                WeekDayRow(date: day,
                           seconds: secs,
                           rate: schedule.hourlyRate,
                           isToday: cal.isDateInToday(day),
                           maxSec: maxSec)
            }
            Divider().padding(.vertical, 2)
            HStack {
                Text("Week total")
                    .font(.callout.weight(.medium))
                    .foregroundStyle(.secondary)
                Spacer()
                Text(WTFormat.hoursMinutes(seconds: weekTotal))
                    .monospacedDigit()
                Text(WTFormat.money(Double(weekTotal) * schedule.hourlyRate / 3600.0))
                    .monospacedDigit()
                    .foregroundStyle(.green)
            }
        }
    }
}

private struct WeekDayRow: View {
    let date: Date
    let seconds: Int
    let rate: Double
    let isToday: Bool
    let maxSec: Int

    var body: some View {
        let frac = Double(seconds) / Double(maxSec)
        HStack(spacing: 10) {
            Text(WTFormat.dayLabel(date))
                .font(.callout)
                .foregroundStyle(isToday ? .primary : .secondary)
                .frame(width: 64, alignment: .leading)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Color.secondary.opacity(0.2))
                    RoundedRectangle(cornerRadius: 3)
                        .fill(seconds > 0 ? Color.green.opacity(isToday ? 0.9 : 0.6) : Color.clear)
                        .frame(width: geo.size.width * frac)
                }
            }
            .frame(height: 6)
            Text(seconds > 0 ? WTFormat.hoursMinutes(seconds: seconds) : "—")
                .font(.callout.monospacedDigit())
                .foregroundStyle(seconds > 0 ? .primary : .secondary)
                .frame(width: 64, alignment: .trailing)
        }
    }
}

// MARK: - Next shift

private struct NextShiftSection: View {
    let schedule: Schedule

    var body: some View {
        let now = Date()
        SectionCard(title: "NEXT SHIFT") {
            if let next = WTMath.nextBlock(blocks: schedule.blocks, now: now) {
                let inSec = max(0, next.start - Int(now.timeIntervalSince1970))
                HStack {
                    VStack(alignment: .leading) {
                        Text(WTFormat.dayPlusTime(next.startDate))
                            .font(.callout)
                        Text("Starts \(WTFormat.relative(seconds: inSec))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(WTFormat.money(Double(next.durationSeconds) * schedule.hourlyRate / 3600.0))
                        .monospacedDigit()
                        .foregroundStyle(.green)
                }
            } else if WTMath.allTime(blocks: schedule.blocks, now: now).activeStart != nil {
                DimRow("Currently working — no upcoming shifts queued.")
            } else {
                DimRow("No upcoming shifts in the next \(WTEventKitPoller.lookaheadDays) days.")
            }
        }
    }
}

// MARK: - All-time

private struct AllTimeSection: View {
    let schedule: Schedule

    var body: some View {
        let now = Date()
        let computed = WTMath.allTime(blocks: schedule.blocks, now: now)
        let nowS = Int(now.timeIntervalSince1970)
        let totalSec = computed.totalSeconds(now: nowS)
        let totalDollars = computed.totalDollars(now: nowS, hourlyRate: schedule.hourlyRate)

        SectionCard(title: "ALL TIME") {
            InfoRow(label: "Hours worked", value: WTFormat.hoursMinutes(seconds: totalSec))
            InfoRow(label: "Total gross", value: WTFormat.money(totalDollars), valueColor: .green)
            InfoRow(label: "Rate", value: WTFormat.rateText(schedule.hourlyRate))
            InfoRow(label: "Event title", value: schedule.workEventTitle.isEmpty ? "—" : schedule.workEventTitle)
        }
    }
}

// MARK: - Permission gate (full)

private struct PermissionGateCard: View {
    @EnvironmentObject var model: WTAppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "calendar.badge.exclamationmark")
                    .foregroundStyle(.orange)
                Text("Calendar access needed")
                    .font(.headline)
            }
            Text("WorkTick reads work events from your iOS Calendar to compute earnings. Grant access to continue.")
                .font(.callout)
                .foregroundStyle(.secondary)
            HStack {
                if model.authStatus == .denied || model.authStatus == .restricted || model.authStatus == .writeOnly {
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                } else {
                    Button("Grant access") {
                        Task { await model.requestAccess() }
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.orange.opacity(0.1))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

// MARK: - Permissions row (mini, always visible)

private struct PermissionsRow: View {
    @EnvironmentObject var model: WTAppModel

    var body: some View {
        let ok = model.authStatus.isUsable
        SectionCard(title: "PERMISSIONS") {
            HStack {
                Image(systemName: ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                    .foregroundStyle(ok ? .green : .orange)
                Text("Calendar access")
                Spacer()
                Text(ok ? "Granted" : "Not granted")
                    .foregroundStyle(.secondary)
            }
            if !ok {
                Button("Open iOS Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .font(.callout)
            }
        }
    }
}

// MARK: - Footer

private struct FooterRow: View {
    @EnvironmentObject var model: WTAppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let when = model.settingsRef.lastPollAt {
                let ago = Int(Date().timeIntervalSince(when))
                Text("Last sync \(WTFormat.relative(seconds: -ago))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if let err = model.lastError {
                Text("⚠︎ \(err)")
                    .font(.caption2)
                    .foregroundStyle(.red)
                    .lineLimit(3)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
    }
}

// MARK: - Empty state

private struct EmptyScheduleCard: View {
    @EnvironmentObject var model: WTAppModel

    var body: some View {
        VStack(spacing: 8) {
            Text("No schedule yet")
                .font(.headline)
            Text("Pull to refresh, or check your event title in Settings.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Refresh") { Task { await model.refresh() } }
                .buttonStyle(.bordered)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color.secondary.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

// MARK: - Reusable bits

private struct SectionCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.bottom, 2)
            content()
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

private struct DimRow: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.callout).foregroundStyle(.secondary)
    }
}

private struct BlockRow: View {
    let block: WorkBlock
    let rate: Double
    let now: Date

    var body: some View {
        let state = block.state(now: now)
        HStack(spacing: 8) {
            StateChip(state: state)
            VStack(alignment: .leading) {
                Text("\(WTFormat.clockTime(block.startDate)) – \(WTFormat.clockTime(block.endDate))")
                    .font(.callout)
                Text(WTFormat.hoursMinutes(seconds: block.durationSeconds))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text(WTFormat.money(Double(block.durationSeconds) * rate / 3600.0))
                .monospacedDigit()
                .foregroundStyle(state == .active ? .green : .primary)
        }
    }
}

private struct StateChip: View {
    let state: WTBlockState

    var body: some View {
        Text(state.label)
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(background)
            .foregroundStyle(foreground)
            .clipShape(Capsule())
    }

    private var background: Color {
        switch state {
        case .active: return .green
        case .done:   return .secondary.opacity(0.3)
        case .queued: return .blue.opacity(0.2)
        }
    }
    private var foreground: Color {
        switch state {
        case .active: return .white
        case .done:   return .secondary
        case .queued: return .blue
        }
    }
}

private struct TotalRow: View {
    let label: String
    let seconds: Int
    let rate: Double

    var body: some View {
        HStack {
            Text(label)
                .font(.callout.weight(.medium))
                .foregroundStyle(.secondary)
            Spacer()
            Text(WTFormat.hoursMinutes(seconds: seconds))
                .monospacedDigit()
            Text(WTFormat.money(Double(seconds) * rate / 3600.0))
                .monospacedDigit()
                .foregroundStyle(.green)
        }
    }
}

private struct InfoRow: View {
    let label: String
    let value: String
    var valueColor: Color = .primary

    var body: some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).monospacedDigit().foregroundStyle(valueColor)
        }
    }
}
