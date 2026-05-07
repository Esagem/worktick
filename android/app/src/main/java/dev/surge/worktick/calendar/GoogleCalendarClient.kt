package dev.surge.worktick.calendar

import dev.surge.worktick.auth.GoogleAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Direct Google Calendar API client. Replaces the FastAPI backend's
 * google_client.py.
 */
class GoogleCalendarClient(private val auth: GoogleAuthManager) {

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    data class Calendar(val id: String, val summary: String, val primary: Boolean)

    data class Event(
        val id: String,
        val iCalUID: String?,
        val summary: String,
        val startMs: Long,
        val endMs: Long
    )

    suspend fun listCalendars(): List<Calendar> = withContext(Dispatchers.IO) {
        val token = auth.getAccessToken()
        val req = Request.Builder()
            .url("$BASE/users/me/calendarList")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("listCalendars HTTP ${resp.code}")
            val json = JSONObject(resp.body!!.string())
            val items = json.optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map {
                val o = items.getJSONObject(it)
                Calendar(
                    id = o.getString("id"),
                    summary = o.optString("summary", o.getString("id")),
                    primary = o.optBoolean("primary", false)
                )
            }
        }
    }

    suspend fun listEvents(
        calendarId: String,
        timeMinIso: String,
        timeMaxIso: String
    ): List<Event> = withContext(Dispatchers.IO) {
        val token = auth.getAccessToken()
        val all = mutableListOf<Event>()
        var pageToken: String? = null
        do {
            val urlBuilder = "$BASE/calendars/${java.net.URLEncoder.encode(calendarId, "UTF-8")}/events"
                .toHttpUrl().newBuilder()
                .addQueryParameter("timeMin", timeMinIso)
                .addQueryParameter("timeMax", timeMaxIso)
                .addQueryParameter("singleEvents", "true")
                .addQueryParameter("orderBy", "startTime")
                .addQueryParameter("maxResults", "250")
                .addQueryParameter("showDeleted", "false")
            if (pageToken != null) urlBuilder.addQueryParameter("pageToken", pageToken)
            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("listEvents HTTP ${resp.code}")
                val json = JSONObject(resp.body!!.string())
                val items = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val start = parseTime(o.optJSONObject("start")) ?: continue
                    val end = parseTime(o.optJSONObject("end")) ?: continue
                    if (end <= start) continue
                    all.add(
                        Event(
                            id = o.getString("id"),
                            iCalUID = o.optString("iCalUID", null),
                            summary = (o.optString("summary", "") ?: "").trim(),
                            startMs = start,
                            endMs = end
                        )
                    )
                }
                pageToken = json.optString("nextPageToken", "").takeIf { it.isNotEmpty() }
            }
        } while (pageToken != null)
        all
    }

    private fun parseTime(o: JSONObject?): Long? {
        if (o == null) return null
        o.optString("dateTime", null)?.takeIf { it.isNotEmpty() }?.let {
            return try {
                java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli()
            } catch (_: Exception) { null }
        }
        o.optString("date", null)?.takeIf { it.isNotEmpty() }?.let {
            return try {
                java.time.LocalDate.parse(it)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            } catch (_: Exception) { null }
        }
        return null
    }

    companion object {
        private const val BASE = "https://www.googleapis.com/calendar/v3"
    }
}
