// WTScheduleStore.swift
// Persists the most recent Schedule to a JSON file in the App Group container
// so both the host app and the widget extension can read it without network or
// EventKit re-permission. Atomic write via temp file + rename matches the
// Android ScheduleStore semantics.

import Foundation

public final class WTScheduleStore {
    public static let shared = WTScheduleStore()

    private let appGroup: String
    private let filename = "schedule.json"

    public init(appGroup: String = WTSettingsKey.appGroup) {
        self.appGroup = appGroup
    }

    private var fileURL: URL? {
        guard let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroup)
        else {
            // Fallback: app's own caches dir (works for local dev without App Group;
            // widget extension won't see it but that's expected without proper setup).
            let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            return dir?.appendingPathComponent(filename)
        }
        let cacheDir = container.appendingPathComponent("Library/Caches", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        return cacheDir.appendingPathComponent(filename)
    }

    public func write(_ schedule: Schedule) throws {
        guard let url = fileURL else { throw CocoaError(.fileWriteUnknown) }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(schedule)
        // Write to temp + rename for atomicity.
        let tmp = url.appendingPathExtension("tmp")
        try data.write(to: tmp, options: .atomic)
        if FileManager.default.fileExists(atPath: url.path) {
            _ = try FileManager.default.replaceItemAt(url, withItemAt: tmp)
        } else {
            try FileManager.default.moveItem(at: tmp, to: url)
        }
    }

    public func read() -> Schedule? {
        guard let url = fileURL,
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Schedule.self, from: data)
    }

    public func clear() {
        if let url = fileURL {
            try? FileManager.default.removeItem(at: url)
        }
    }
}
