// WorkTickLiveActivity.swift
// iOS 16.1+ Live Activity. Lock-screen + Dynamic Island UI for active work
// blocks. Apple supports `Text(timerInterval:)` here for live time displays
// and `TimelineView(.periodic(...))` for periodic redraws of arbitrary content.
//
// Lifecycle is owned by WTLiveActivityController in the host app — see
// Platform/WTLiveActivityController.swift.

import ActivityKit
import WidgetKit
import SwiftUI

struct WTLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: WTActivityAttributes.self) { context in
            // Lock screen / banner UI
            let tick = WTFormat.tickInterval(hourlyRate: context.attributes.hourlyRate)
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Circle().fill(.green).frame(width: 8, height: 8)
                    Text("On the clock · WorkTick")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(timerInterval: context.attributes.startedAt...Date.distantFuture, countsDown: false)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
                TimelineView(.periodic(from: Date(), by: tick)) { ctx in
                    Text(WTFormat.money(context.attributes.dollars(at: ctx.date)))
                        .font(.system(size: 36, weight: .bold, design: .rounded))
                        .monospacedDigit()
                }
                Text("@ \(WTFormat.rateText(context.attributes.hourlyRate))")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            .padding()
            .activityBackgroundTint(.black.opacity(0.4))
        } dynamicIsland: { context in
            let tick = WTFormat.tickInterval(hourlyRate: context.attributes.hourlyRate)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading) {
                        Text("Working").font(.caption2).foregroundStyle(.green)
                        Text(timerInterval: context.attributes.startedAt...Date.distantFuture, countsDown: false)
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    TimelineView(.periodic(from: Date(), by: tick)) { ctx in
                        Text(WTFormat.money(context.attributes.dollars(at: ctx.date)))
                            .font(.title3.bold().monospacedDigit())
                            .foregroundStyle(.green)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text("\(WTFormat.rateText(context.attributes.hourlyRate)) gross")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            } compactLeading: {
                Image(systemName: "dollarsign.circle.fill").foregroundStyle(.green)
            } compactTrailing: {
                TimelineView(.periodic(from: Date(), by: tick)) { ctx in
                    Text(WTFormat.money(context.attributes.dollars(at: ctx.date)))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.green)
                }
            } minimal: {
                Image(systemName: "dollarsign.circle.fill").foregroundStyle(.green)
            }
        }
    }
}
