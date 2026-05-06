package dev.surge.worktick

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Blocking HTTP helpers for the WorkTick backend. Callers run these on a
 * background thread; results are persisted to ScheduleStore + the widget is
 * refreshed on success. Both ScheduleFetchWorker and MainActivity (manual
 * sync, edit-rate, edit-title) use these.
 */
object BackendClient {

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val pollClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(45, TimeUnit.SECONDS)  // Calendar polling can be slow
            .build()
    }

    private fun authHeader() = "Bearer ${BuildConfig.API_SECRET}"

    /** GET /schedule, parse, persist to ScheduleStore, kick the widget to redraw. */
    @Throws(IOException::class)
    fun fetchSchedule(context: Context): Schedule {
        val req = Request.Builder()
            .url("${BuildConfig.BACKEND_URL}/schedule")
            .header("Authorization", authHeader())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("empty body")
            val j = JSONObject(body)
            val arr = j.getJSONArray("blocks")
            val blocks = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Schedule.Block(o.getLong("start"), o.getLong("end"))
            }
            val schedule = Schedule(
                fetchedAt = if (j.isNull("fetched_at")) System.currentTimeMillis() / 1000
                            else j.getLong("fetched_at"),
                hourlyRate = j.getDouble("hourly_rate"),
                blocks = blocks,
                workEventTitle = j.optString("work_event_title", "")
            )
            ScheduleStore.write(context, schedule)
            MoneyTickerWidgetProvider.requestUpdate(context)
            return schedule
        }
    }

    /** POST /admin/poll — forces an immediate Calendar poll on the backend. */
    @Throws(IOException::class)
    fun forcePoll() {
        val req = Request.Builder()
            .url("${BuildConfig.BACKEND_URL}/admin/poll")
            .header("Authorization", authHeader())
            .post("".toRequestBody(null))
            .build()
        pollClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        }
    }

    /** POST /admin/config — partial update of hourly_rate and/or work_event_title. */
    @Throws(IOException::class)
    fun updateConfig(rate: Double? = null, title: String? = null) {
        val payload = JSONObject().apply {
            rate?.let { put("hourly_rate", it) }
            title?.let { put("work_event_title", it) }
        }.toString()
        val req = Request.Builder()
            .url("${BuildConfig.BACKEND_URL}/admin/config")
            .header("Authorization", authHeader())
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        // Title changes trigger a synchronous poll, so use the slower client.
        val c = if (title != null) pollClient else client
        c.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
        }
    }
}
