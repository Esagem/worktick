// WTLiveActivityController.swift
// Owns Live Activity lifecycle. Extracted from the monolithic WTManager so the
// rules (start when a block is active, end at block end, restart if attributes
// changed) live in one testable place.

import Foundation
import ActivityKit

@MainActor
public final class WTLiveActivityController {
    public static let shared = WTLiveActivityController()

    private var current: Activity<WTActivityAttributes>?
    private let settings: WTSettings
    public private(set) var lastError: String?

    public init(settings: WTSettings = .shared) {
        self.settings = settings
        // On launch, adopt any in-flight activity left over from a previous run
        // (e.g. app was killed but block is still active).
        self.current = Activity<WTActivityAttributes>.activities.first
    }

    /// Reconcile against the current schedule. Idempotent — call freely.
    public func reconcile(schedule: Schedule, now: Date = Date()) {
        // Opt-in only. If the user disabled Live Activities in Settings, end
        // any leftover activity and never start a new one.
        guard settings.liveActivityEnabled else {
            Task { await stopCurrent() }
            return
        }

        let computed = WTMath.allTime(blocks: schedule.blocks, now: now)

        if let activeStart = computed.activeStart {
            // Block active: ensure activity is running and matches.
            if let act = current,
               act.attributes.startedAt == Date(timeIntervalSince1970: TimeInterval(activeStart)),
               act.attributes.hourlyRate == schedule.hourlyRate {
                // Already running for this block — bump generation so the system
                // re-renders at least once after reconcile.
                Task { await bumpGeneration(act) }
                return
            }
            // Either not running, or running for a stale block / old rate.
            Task { await stopCurrent() ; start(schedule: schedule, activeStart: activeStart, computed: computed, now: now) }
        } else {
            // No active block — end any running activity.
            Task { await stopCurrent() }
        }
    }

    public func stop() {
        Task { await stopCurrent() }
    }

    // MARK: - private

    private func start(schedule: Schedule, activeStart: Int, computed: WTComputed, now: Date) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            lastError = "Live Activities are disabled in Settings."
            return
        }

        let blockEnd: Date = WTMath.currentBlockEnd(blocks: schedule.blocks, now: now)
            ?? now.addingTimeInterval(8 * 3600)  // safety cap

        let attrs = WTActivityAttributes(
            hourlyRate: schedule.hourlyRate,
            startedAt: Date(timeIntervalSince1970: TimeInterval(activeStart)),
            completedSecondsBefore: computed.completedSeconds
        )
        let state = WTActivityAttributes.ContentState(generation: 0)
        let content = ActivityContent(state: state, staleDate: blockEnd.addingTimeInterval(60))

        do {
            current = try Activity<WTActivityAttributes>.request(
                attributes: attrs,
                content: content,
                pushType: nil
            )
            lastError = nil
        } catch {
            lastError = "Activity start failed: \(error.localizedDescription)"
        }
    }

    private func stopCurrent() async {
        guard let act = current else { return }
        await act.end(nil, dismissalPolicy: .immediate)
        current = nil
    }

    private func bumpGeneration(_ activity: Activity<WTActivityAttributes>) async {
        let next = activity.content.state.generation &+ 1
        let state = WTActivityAttributes.ContentState(generation: next)
        let content = ActivityContent(state: state, staleDate: activity.content.staleDate)
        await activity.update(content)
    }
}
