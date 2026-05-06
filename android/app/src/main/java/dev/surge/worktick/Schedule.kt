package dev.surge.worktick

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Mirrors backend /schedule payload. */
data class Schedule(
    val fetchedAt: Long,
    val hourlyRate: Double,
    val blocks: List<Block>,
    val plannedShiftHours: Double = 8.0,
    val workEventTitle: String = ""
) {
    data class Block(val start: Long, val end: Long)

    fun toJson(): String = JSONObject().apply {
        put("fetched_at", fetchedAt)
        put("hourly_rate", hourlyRate)
        put("planned_shift_hours", plannedShiftHours)
        put("work_event_title", workEventTitle)
        put("blocks", JSONArray().apply {
            blocks.forEach { put(JSONObject().apply { put("start", it.start); put("end", it.end) }) }
        })
    }.toString()

    companion object {
        fun fromJson(s: String): Schedule? = try {
            val j = JSONObject(s)
            val arr = j.getJSONArray("blocks")
            val blocks = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Block(o.getLong("start"), o.getLong("end"))
            }
            Schedule(
                fetchedAt = j.getLong("fetched_at"),
                hourlyRate = j.getDouble("hourly_rate"),
                blocks = blocks,
                plannedShiftHours = j.optDouble("planned_shift_hours", 8.0),
                workEventTitle = j.optString("work_event_title", "")
            )
        } catch (e: Exception) { null }
    }
}

object ScheduleStore {
    private const val PREFS = "worktick"
    private const val KEY = "schedule_json"

    fun write(context: Context, s: Schedule) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, s.toJson()) }
    }

    fun read(context: Context): Schedule? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return Schedule.fromJson(raw)
    }
}
