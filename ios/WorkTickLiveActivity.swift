// WorkTickLiveActivity.swift
// iOS 16.1+ Live Activity. The host app starts one of these when a work block becomes active.
// Live Activities can update at higher frequency than home screen widgets, and Apple
// explicitly supports `Text(timerInterval:)` here for live time displays.
//
// To start one from the host app:
//
//   import ActivityKit
//   let attrs = WTActivityAttributes(hourlyRate: schedule.hourlyRate,
//                                    startedAt: Date(timeIntervalSince1970: TimeInterval(activeStart)),
//                                    completedSecondsBefore: completedSeconds)
//   let state = WTActivityAttributes.ContentState(generation: 0)
//   let content = ActivityContent(state: state, staleDate: blockEnd.addingTimeInterval(60))
//   try Activity<WTActivityAttributes>.request(attributes: attrs, content: content,
//                                              pushType: nil)
//
// The activity ends automatically at staleDate, or call `activity.end(...)` at block end.

import ActivityKit
import WidgetKit
import SwiftUI

struct WTActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        // Bumping `generation` forces a refresh; the visible content is otherwise
        // computed from the static attributes + current time.
        var generation: Int
    }

    var hourlyRate: Double
    var startedAt: Date              // when the active block started
    var completedSecondsBefore: Int  // sum of all PRIOR completed blocks before this one started
}

private extension WTActivityAttributes {
    /// Helper: dollars at a given time.
    func dollars(at now: Date) -> Double {
        let elapsed = max(0, now.timeIntervalSince(startedAt))
        let totalSec = Double(completedSecondsBefore) + elapsed
        return totalSec * hourlyRate / 3600.0
    }
}

private let liveMoneyFormatter: NumberFormatter = {
    let f = NumberFormatter()
    f.numberStyle = .currency
    f.locale = Locale(identifier: "en_US")
    f.minimumFractionDigits = 2
    f.maximumFractionDigits = 2
    return f
}()

private func liveFormatMoney(_ amount: Double) -> String {
    liveMoneyFormatter.string(from: NSNumber(value: amount)) ?? "$0.00"
}

/// Tick interval (seconds) derived from hourly rate — twice per penny for fluidity.
/// At $rate/hr, seconds-per-penny = 3600 / (rate*100) = 36/rate.
/// Clamped to [0.25, 1.0] so we don't burn battery at low rates or miss pennies at high ones.
private func tickInterval(hourlyRate: Double) -> Double {
    guard hourlyRate > 0 else { return 1.0 }
    let secondsPerPenny = 36.0 / hourlyRate
    let ideal = secondsPerPenny / 2.0
    return min(1.0, max(0.25, ideal))
}

struct WTLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: WTActivityAttributes.self) { context in
            // Lock screen / banner UI
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Circle().fill(.green).frame(width: 8, height: 8)
                    Text("On the clock · WorkTick")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(timerInterval: context.attributes.startedAt...Date.distantFuture,
                         countsDown: false)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
                TimelineView(.periodic(from: Date(), by: tickInterval(hourlyRate: context.attributes.hourlyRate))) { ctx in
                    Text(liveFormatMoney(context.attributes.dollars(at: ctx.date)))
                        .font(.system(size: 36, weight: .bold, design: .rounded))
                        .monospacedDigit()
                }
                Text(String(format: "@ %@/h", liveFormatMoney(context.attributes.hourlyRate)))
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            .padding()
            .activityBackgroundTint(.black.opacity(0.4))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading) {
                        Text("Working").font(.caption2).foregroundStyle(.green)
                        Text(timerInterval: context.attributes.startedAt...Date.distantFuture, countsDown: false)
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    TimelineView(.periodic(from: Date(), by: tickInterval(hourlyRate: context.attributes.hourlyRate))) { ctx in
                        Text(liveFormatMoney(context.attributes.dollars(at: ctx.date)))
                            .font(.title3.bold().monospacedDigit())
                            .foregroundStyle(.green)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(String(format: "%@/h gross", liveFormatMoney(context.attributes.hourlyRate)))
                        .font(.caption2).foregroundStyle(.secondary)
                }
            } compactLeading: {
                Image(systemName: "dollarsign.circle.fill").foregroundStyle(.green)
            } compactTrailing: {
                TimelineView(.periodic(from: Date(), by: tickInterval(hourlyRate: context.attributes.hourlyRate))) { ctx in
                    Text(liveFormatMoney(context.attributes.dollars(at: ctx.date)))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.green)
                }
            } minimal: {
                Image(systemName: "dollarsign.circle.fill").foregroundStyle(.green)
            }
        }
    }
}
