// WTSchedule.swift
// Schedule data model. Mirrors Android Schedule.kt — same field names so a
// schedule cached on one platform is readable by the other (handy for shared
// fixtures, log dumps, debugging).
//
// All instants are stored as Int unix seconds. On 64-bit Apple platforms Int
// is 64-bit so this is equivalent to Android's Long.

import Foundation

public struct WorkBlock: Codable, Hashable, Identifiable {
    public let start: Int
    public let end: Int

    public var id: Int { start }

    public init(start: Int, end: Int) {
        self.start = start
        self.end = end
    }

    public var startDate: Date { Date(timeIntervalSince1970: TimeInterval(start)) }
    public var endDate: Date { Date(timeIntervalSince1970: TimeInterval(end)) }
    public var durationSeconds: Int { max(0, end - start) }
}

public struct Schedule: Codable, Hashable {
    public let fetchedAt: Int
    public let hourlyRate: Double
    public let blocks: [WorkBlock]
    public let plannedShiftHours: Double
    public let workEventTitle: String

    public init(
        fetchedAt: Int,
        hourlyRate: Double,
        blocks: [WorkBlock],
        plannedShiftHours: Double = 8.0,
        workEventTitle: String = ""
    ) {
        self.fetchedAt = fetchedAt
        self.hourlyRate = hourlyRate
        self.blocks = blocks
        self.plannedShiftHours = plannedShiftHours
        self.workEventTitle = workEventTitle
    }

    enum CodingKeys: String, CodingKey {
        case fetchedAt = "fetched_at"
        case hourlyRate = "hourly_rate"
        case blocks
        case plannedShiftHours = "planned_shift_hours"
        case workEventTitle = "work_event_title"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.fetchedAt = try c.decode(Int.self, forKey: .fetchedAt)
        self.hourlyRate = try c.decode(Double.self, forKey: .hourlyRate)
        self.blocks = try c.decode([WorkBlock].self, forKey: .blocks)
        self.plannedShiftHours = (try? c.decode(Double.self, forKey: .plannedShiftHours)) ?? 8.0
        self.workEventTitle = (try? c.decode(String.self, forKey: .workEventTitle)) ?? ""
    }
}
