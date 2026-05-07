// WorkTickHostApp.swift
// @main entry. Slim — almost everything lives in WTAppModel and the views.
//
// Required Xcode capabilities (see docs/IOS.md):
//   • App Groups (group.dev.surge.worktick) — host app + widget extension
//   • Background Modes: Background fetch + Background processing
//   • Live Activities (NSSupportsLiveActivities = YES)
//
// Required Info.plist keys:
//   • NSCalendarsFullAccessUsageDescription   (iOS 17+)
//   • NSCalendarsUsageDescription             (legacy)
//   • BGTaskSchedulerPermittedIdentifiers     containing dev.surge.worktick.refresh

import SwiftUI
import BackgroundTasks

@main
struct WorkTickApp: App {
    @StateObject private var model = WTAppModel.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            DashboardView()
                .environmentObject(model)
                .task { await model.refresh() }
                .onChange(of: scenePhase) { phase in
                    if phase == .active {
                        Task { await model.refresh() }
                    }
                }
        }
        .backgroundTask(.appRefresh(WTBackgroundIdentifier.refresh)) {
            await WTAppModel.shared.refresh()
            WTBackgroundScheduler.shared.scheduleAll()
        }
    }
}
