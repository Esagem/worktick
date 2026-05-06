package dev.surge.worktick

import kotlin.math.max

data class Computed(
    val completedSeconds: Long,
    val activeStart: Long?,
    val lastEndedBlock: Schedule.Block? = null
) {
    fun totalSeconds(now: Long): Long =
        if (activeStart != null) completedSeconds + max(0L, now - activeStart) else completedSeconds
    fun totalDollars(now: Long, hourlyRate: Double): Double =
        totalSeconds(now).toDouble() * hourlyRate / 3600.0
    fun activeShiftSeconds(now: Long): Long =
        if (activeStart != null) max(0L, now - activeStart) else 0L

    /**
     * Money earned with sub-second precision. Required for the cent ticker — the
     * second-precision variant only updates once per second, so cents (which tick
     * every 1.2s at $30/hr) get a 1-cent lag that catches up via skip-and-freeze
     * every 5–6 ticks.
     */
    fun totalDollarsMs(nowMs: Long, hourlyRate: Double): Double {
        val baseMs = completedSeconds * 1000L
        val totalMs = if (activeStart != null) baseMs + max(0L, nowMs - activeStart * 1000L) else baseMs
        return totalMs.toDouble() * hourlyRate / 3_600_000.0
    }

    /**
     * Hours for the currently-relevant shift — drives the bottom-row "SHIFT" label
     * and the progress bar. Active shift wins; otherwise we fall back to the most
     * recently completed block as long as it ended on or after [todayStart]
     * (i.e. today, in the user's local timezone). Before today's first shift this
     * returns 0; after today's last shift it returns the full duration so the bar
     * stays maxed out instead of snapping back to empty.
     */
    fun currentShiftSeconds(now: Long, todayStart: Long): Long {
        if (activeStart != null) return max(0L, now - activeStart)
        val last = lastEndedBlock ?: return 0L
        if (last.end < todayStart) return 0L
        return last.end - last.start
    }
}

object Math {
    fun allTime(blocks: List<Schedule.Block>, now: Long): Computed {
        var completed = 0L
        var active: Long? = null
        var lastEnded: Schedule.Block? = null
        for (b in blocks) {
            when {
                b.end <= now -> {
                    completed += (b.end - b.start)
                    if (lastEnded == null || b.end > lastEnded.end) lastEnded = b
                }
                b.start <= now && now < b.end -> active = b.start
            }
        }
        return Computed(completed, active, lastEnded)
    }
}
