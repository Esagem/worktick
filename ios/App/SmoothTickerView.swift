// SmoothTickerView.swift
// Full-screen, ProMotion-friendly money ticker. Mirrors Android SmoothActivity.
// Uses TimelineView(.animation) so SwiftUI redraws on every display refresh
// (60 Hz on standard, 120 Hz on ProMotion). The dollar string is computed
// each frame at millisecond precision via WTComputed.totalDollarsMs.

import SwiftUI

struct SmoothTickerView: View {
    @EnvironmentObject var model: WTAppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let s = model.schedule {
                TimelineView(.animation(minimumInterval: nil, paused: false)) { ctx in
                    let computed = WTMath.allTime(blocks: s.blocks, now: ctx.date)
                    let nowMs = Int64(ctx.date.timeIntervalSince1970 * 1000)
                    let dollars = computed.totalDollarsMs(nowMs: nowMs, hourlyRate: s.hourlyRate)
                    let active = computed.activeStart != nil

                    VStack(spacing: 16) {
                        StatusLine(active: active, schedule: s, computed: computed, now: ctx.date)
                        Text(WTFormat.money(dollars))
                            .font(.system(size: 96, weight: .bold, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(active ? .green : .white)
                            .minimumScaleFactor(0.4)
                            .lineLimit(1)
                            .padding(.horizontal, 24)
                        Text(WTFormat.rateText(s.hourlyRate))
                            .font(.title3.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                ProgressView().tint(.white)
            }

            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title)
                            .foregroundStyle(.white.opacity(0.5))
                            .padding()
                    }
                }
                Spacer()
            }
        }
        .statusBarHidden()
        .onTapGesture { dismiss() }
    }
}

private struct StatusLine: View {
    let active: Bool
    let schedule: Schedule
    let computed: WTComputed
    let now: Date

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(active ? .green : .gray)
                .frame(width: 10, height: 10)
            if active, let start = computed.activeStart {
                Text(timerInterval: Date(timeIntervalSince1970: TimeInterval(start))...Date.distantFuture, countsDown: false)
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.7))
            } else {
                Text("Off the clock")
                    .font(.callout)
                    .foregroundStyle(.white.opacity(0.7))
            }
        }
    }
}
