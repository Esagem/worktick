// WorkTickConfig.example.swift
//
// Optional debug overrides. The app runs fine without this file — copy to
// WorkTickConfig.swift only if you need one of the debug switches below.
// WorkTickConfig.swift is gitignored.
//
// As of the EventKit refactor, WorkTick no longer requires a backend or a
// shared API secret on iOS. Calendar access is granted by the user via the
// system permission prompt; events come from EventKit directly.

import Foundation

enum WorkTickConfig {
    /// Force-active mode: when true, the dashboard pretends the user is
    /// currently working even if no calendar block is active. Useful for
    /// screenshotting the live ticker UI. Leave false in normal use.
    static let debugForceActiveBlock: Bool = false

    /// Override the EventKit polling lookahead (days). Default 14, defined
    /// in WTEventKitPoller.lookaheadDays.
    static let debugLookaheadDaysOverride: Int? = nil
}
