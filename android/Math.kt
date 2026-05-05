package dev.surge.worktick

import kotlin.math.max

data class Computed(val completedSeconds: Long, val activeStart: Long?) {
    fun totalSeconds(now: Long): Long =
        if (activeStart != null) completedSeconds + max(0L, now - activeStart) else completedSeconds
    fun totalDollars(now: Long, hourlyRate: Double): Double =
        totalSeconds(now).toDouble() * hourlyRate / 3600.0
}

object Math {
    /** All-time totals. Active block (start ≤ now < end) is reported separately so the
     *  widget can render a live tick. */
    fun allTime(blocks: List<Schedule.Block>, now: Long): Computed {
        var completed = 0L
        var active: Long? = null
        for (b in blocks) {
            when {
                b.end <= now -> completed += (b.end - b.start)
                b.start <= now && now < b.end -> active = b.start
                // future block: skip
            }
        }
        return Computed(completed, active)
    }
}
