package dev.surge.worktick.calendar

import android.content.Context
import android.util.Log
import dev.surge.worktick.Schedule
import dev.surge.worktick.WTSettings
import dev.surge.worktick.auth.GoogleAuthManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Walks all visible calendars, finds events matching the configured title,
 * and produces a Schedule. Replaces backend/poller.py.
 */
class SchedulePoller(private val context: Context) {

    private val auth = GoogleAuthManager(context)
    private val client = GoogleCalendarClient(auth)

    suspend fun pollOnce(): Result {
        if (!auth.isAuthenticated()) {
            return Result.NotAuthenticated
        }
        val targetTitle = WTSettings.eventTitle(context).trim()
        val targetTitleLower = targetTitle.lowercase()
        val rate = WTSettings.hourlyRate(context)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val timeMin = now.minusDays(LOOKBACK_DAYS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val timeMax = now.plusDays(LOOKAHEAD_DAYS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        return try {
            val calendars = client.listCalendars()
            Log.i(TAG, "Polling ${calendars.size} calendars for '$targetTitle'")

            val matchedBlocks = mutableListOf<Schedule.Block>()
            var calendarErrors = 0

            for (cal in calendars) {
                try {
                    val events = client.listEvents(cal.id, timeMin, timeMax)
                    val matches = events.filter { it.summary.trim().lowercase() == targetTitleLower }
                    matches.forEach { e ->
                        matchedBlocks.add(
                            Schedule.Block(start = e.startMs / 1000, end = e.endMs / 1000)
                        )
                    }
                    if (matches.isNotEmpty()) {
                        Log.i(TAG, "  ${cal.summary}: ${matches.size} match(es)")
                    }
                } catch (e: Exception) {
                    calendarErrors++
                    Log.w(TAG, "Failed to read calendar ${cal.id}: ${e.message}")
                }
            }

            // Dedupe by start time (recurrences can show up across multiple shared calendars)
            val unique = matchedBlocks.distinctBy { it.start }.sortedBy { it.start }

            val schedule = Schedule(
                fetchedAt = Instant.now().epochSecond,
                hourlyRate = rate,
                blocks = unique,
                workEventTitle = targetTitle
            )
            WTSettings.setLastPollAt(context, Instant.now().epochSecond)
            WTSettings.setLastPollError(context, null)
            Log.i(TAG, "Poll OK: ${unique.size} blocks, $calendarErrors calendar errors")
            Result.Ok(schedule)
        } catch (e: GoogleAuthManager.NotAuthenticatedException) {
            WTSettings.setLastPollError(context, "not_authenticated")
            Result.NotAuthenticated
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            Log.e(TAG, "Poll failed: $msg", e)
            WTSettings.setLastPollError(context, msg)
            Result.Error(msg)
        }
    }

    sealed class Result {
        data class Ok(val schedule: Schedule) : Result()
        data object NotAuthenticated : Result()
        data class Error(val message: String) : Result()
    }

    companion object {
        private const val TAG = "SchedulePoller"
        private const val LOOKBACK_DAYS = 365L
        private const val LOOKAHEAD_DAYS = 14L
    }
}
