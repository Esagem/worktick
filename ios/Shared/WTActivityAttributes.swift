// WTActivityAttributes.swift
// Shape of a Live Activity. Used by both the host app (WTLiveActivityController
// requests/updates the activity) and the widget extension (WTLiveActivity
// renders it). Must live in Shared/ so it's a member of both targets.

import Foundation
import ActivityKit

public struct WTActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        // Bumping `generation` forces a refresh; visible content is otherwise
        // computed from the static attributes + current time.
        public var generation: Int

        public init(generation: Int) {
            self.generation = generation
        }
    }

    public var hourlyRate: Double
    public var startedAt: Date              // when the active block started
    public var completedSecondsBefore: Int  // sum of all PRIOR completed blocks

    public init(hourlyRate: Double, startedAt: Date, completedSecondsBefore: Int) {
        self.hourlyRate = hourlyRate
        self.startedAt = startedAt
        self.completedSecondsBefore = completedSecondsBefore
    }

    /// Dollars at a given time, computed from the attributes alone.
    public func dollars(at now: Date) -> Double {
        let elapsed = max(0, now.timeIntervalSince(startedAt))
        let totalSec = Double(completedSecondsBefore) + elapsed
        return totalSec * hourlyRate / 3600.0
    }
}
