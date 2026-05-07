// WTMath.swift
// Pure earnings/time math. Bit-for-bit port of android/.../Math.kt — the same
// inputs must produce the same outputs on both platforms so the live ticker
// agrees with the dashboard everywhere.
//
// No UIKit/AppKit imports — usable from app, widget extension, and Live Activity.

import Foundation

public struct WTComputed {
    public let completedSeconds: Int
    public let activeStart: Int?         // unix seconds; nil if not currently working
    public let lastEndedBlock: WorkBlock?

    public init(completedSeconds: Int, activeStart: Int?, lastEndedBlock: WorkBlock?) {
        self.completedSeconds = completedSeconds
        self.activeStart = activeStart
        self.lastEndedBlock = lastEndedBlock
    }

    public func totalSeconds(now: Int) -> Int {
        if let active = activeStart {
            return completedSeconds + max(0, now - active)
        }
        return completedSeconds
    }

    public func totalDollars(now: Int, hourlyRate: Double) -> Double {
        Double(totalSeconds(now: now)) * hourlyRate / 3600.0
    }

    public func activeShiftSeconds(now: Int) -> Int {
        guard let active = activeStart else { return 0 }
        return max(0, now - active)
    }

    /// Sub-second-precision dollars. The cent ticker needs millisecond accuracy
    /// because at $30/hr a penny lands every 1.2 s — second-rounded math drifts.
    public func totalDollarsMs(nowMs: Int64, hourlyRate: Double) -> Double {
        let baseMs = Int64(completedSeconds) * 1000
        let totalMs: Int64
        if let active = activeStart {
            totalMs = baseMs + max(0, nowMs - Int64(active) * 1000)
        } else {
            totalMs = baseMs
        }
        return Double(totalMs) * hourlyRate / 3_600_000.0
    }

    /// Hours for the currently-relevant shift — drives the progress bar.
    /// Active shift wins; otherwise fall back to the most recently completed
    /// block as long as it ended on or after [todayStart]. Before today's
    /// first shift returns 0; after the last shift returns its full duration
    /// so the bar stays maxed instead of snapping to empty.
    public func currentShiftSeconds(now: Int, todayStart: Int) -> Int {
        if let active = activeStart {
            return max(0, now - active)
        }
        guard let last = lastEndedBlock else { return 0 }
        if last.end < todayStart { return 0 }
        return last.end - last.start
    }
}

public enum WTMath {
    public static func allTime(blocks: [WorkBlock], now: Date) -> WTComputed {
        let nowS = Int(now.timeIntervalSince1970)
        var completed = 0
        var active: Int? = nil
        var lastEnded: WorkBlock? = nil
        for b in blocks {
            if b.end <= nowS {
                completed += (b.end - b.start)
                if lastEnded == nil || b.end > (lastEnded?.end ?? Int.min) {
                    lastEnded = b
                }
            } else if b.start <= nowS && nowS < b.end {
                active = b.start
            }
        }
        return WTComputed(completedSeconds: completed, activeStart: active, lastEndedBlock: lastEnded)
    }

    public static func currentBlock(blocks: [WorkBlock], now: Date) -> WorkBlock? {
        let nowS = Int(now.timeIntervalSince1970)
        return blocks.first { $0.start <= nowS && nowS < $0.end }
    }

    public static func currentBlockEnd(blocks: [WorkBlock], now: Date) -> Date? {
        currentBlock(blocks: blocks, now: now)?.endDate
    }

    public static func nextBlock(blocks: [WorkBlock], now: Date) -> WorkBlock? {
        let nowS = Int(now.timeIntervalSince1970)
        return blocks
            .filter { $0.start > nowS }
            .min { $0.start < $1.start }
    }

    public static func nextBlockStart(blocks: [WorkBlock], now: Date) -> Date? {
        nextBlock(blocks: blocks, now: now)?.startDate
    }

    /// Earliest future boundary (start of next block OR end of current block).
    /// Drives both background-task scheduling and Live Activity stale dates.
    public static func nextBoundary(blocks: [WorkBlock], now: Date) -> Date? {
        if let endDate = currentBlockEnd(blocks: blocks, now: now) { return endDate }
        return nextBlockStart(blocks: blocks, now: now)
    }
}
