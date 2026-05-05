// WorkTickHostApp.swift
// Minimal host app that:
//   1. Fetches the schedule periodically (background refresh + foreground)
//   2. Watches for active work blocks and starts a Live Activity automatically
//   3. Ends the Live Activity at block end
//
// This is needed because Widgets cannot start Live Activities — only the app can.
// Add a Background Modes capability with "Background fetch" enabled.

import SwiftUI
import ActivityKit
import BackgroundTasks

@main
struct WorkTickApp: App {
    @StateObject private var manager = WTManager.shared

    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(manager)
                .task { await manager.refreshAndReconcile() }
        }
        .backgroundTask(.appRefresh("dev.surge.worktick.refresh")) {
            await WTManager.shared.refreshAndReconcile()
            await WTManager.shared.scheduleNextRefresh()
        }
    }
}

// MARK: - Manager

@MainActor
final class WTManager: ObservableObject {
    static let shared = WTManager()

    @Published var schedule: ScheduleResponse?
    @Published var lastError: String?
    private var currentActivity: Activity<WTActivityAttributes>?

    func refreshAndReconcile() async {
        do {
            let s = try await WTClient.fetchSchedule()
            self.schedule = s
            self.lastError = nil
            reconcileLiveActivity(schedule: s)
        } catch {
            self.lastError = error.localizedDescription
        }
    }

    /// Starts/stops Live Activity to match the current block state.
    private func reconcileLiveActivity(schedule: ScheduleResponse) {
        let now = Date()
        let computed = WTMath.allTime(blocks: schedule.blocks, now: now)

        if let activeStart = computed.activeBlockStart {
            // Should be running. If not, start it.
            if currentActivity == nil {
                startActivity(schedule: schedule, activeStart: activeStart, computed: computed)
            }
        } else {
            // Should not be running. End any active one.
            if let activity = currentActivity {
                Task {
                    await activity.end(nil, dismissalPolicy: .immediate)
                    self.currentActivity = nil
                }
            }
        }
    }

    private func startActivity(schedule: ScheduleResponse, activeStart: Int, computed: WTComputed) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            self.lastError = "Live Activities are disabled in Settings."
            return
        }
        let blockEnd: Date = {
            let nowS = Int(Date().timeIntervalSince1970)
            for b in schedule.blocks where b.start <= nowS && nowS < b.end {
                return Date(timeIntervalSince1970: TimeInterval(b.end))
            }
            return Date().addingTimeInterval(8 * 3600)  // safety cap
        }()

        let attrs = WTActivityAttributes(
            hourlyRate: schedule.hourlyRate,
            startedAt: Date(timeIntervalSince1970: TimeInterval(activeStart)),
            completedSecondsBefore: computed.completedSeconds
        )
        let state = WTActivityAttributes.ContentState(generation: 0)
        let content = ActivityContent(state: state, staleDate: blockEnd.addingTimeInterval(60))

        do {
            self.currentActivity = try Activity<WTActivityAttributes>.request(
                attributes: attrs, content: content, pushType: nil
            )
        } catch {
            self.lastError = "Activity start failed: \(error.localizedDescription)"
        }
    }

    func scheduleNextRefresh() async {
        let req = BGAppRefreshTaskRequest(identifier: "dev.surge.worktick.refresh")
        // Reconcile state at each block boundary, otherwise every hour.
        var nextDate = Date().addingTimeInterval(3600)
        if let s = schedule {
            let now = Date()
            if let blockEnd = WTMath.currentBlockEnd(blocks: s.blocks, now: now) {
                nextDate = min(nextDate, blockEnd.addingTimeInterval(30))
            } else if let nextStart = WTMath.nextBlockStart(blocks: s.blocks, now: now) {
                nextDate = min(nextDate, nextStart)
            }
        }
        req.earliestBeginDate = nextDate
        try? BGTaskScheduler.shared.submit(req)
    }
}

// MARK: - Minimal UI

struct ContentView: View {
    @EnvironmentObject var manager: WTManager

    var body: some View {
        VStack(spacing: 16) {
            Text("WorkTick").font(.largeTitle.bold())
            if let s = manager.schedule {
                let now = Date()
                let computed = WTMath.allTime(blocks: s.blocks, now: now)
                let active = computed.activeBlockStart != nil
                HStack(spacing: 8) {
                    Circle().fill(active ? .green : .gray).frame(width: 10, height: 10)
                    Text(active ? "On the clock" : "Off")
                }
                Text(formatMoney(computed.totalDollars(at: now, hourlyRate: s.hourlyRate)))
                    .font(.system(size: 48, weight: .bold, design: .rounded))
                    .monospacedDigit()
                Text("\(s.blocks.count) blocks · \(formatMoney(s.hourlyRate))/h")
                    .foregroundStyle(.secondary)
            } else if let err = manager.lastError {
                Text("Error: \(err)").foregroundStyle(.red)
            } else {
                ProgressView()
            }
            Button("Refresh") {
                Task { await manager.refreshAndReconcile() }
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
    }
}
