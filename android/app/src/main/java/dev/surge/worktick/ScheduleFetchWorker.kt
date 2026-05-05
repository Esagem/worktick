package dev.surge.worktick

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that fetches /schedule and persists it.
 * Backend URL and API secret are injected at compile time from local.properties
 * via BuildConfig — see app/build.gradle.kts.
 *
 * After fetching, also reconciles WidgetTickerService — starts it if a block
 * is currently active, stops it otherwise.
 */
class ScheduleFetchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${BuildConfig.BACKEND_URL}/schedule")
                .header("Authorization", "Bearer ${BuildConfig.API_SECRET}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.retry()
                val body = resp.body?.string() ?: return@withContext Result.retry()
                val j = JSONObject(body)
                val arr: JSONArray = j.getJSONArray("blocks")
                val blocks = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Schedule.Block(o.getLong("start"), o.getLong("end"))
                }
                val schedule = Schedule(
                    fetchedAt = if (j.isNull("fetched_at")) System.currentTimeMillis() / 1000
                                else j.getLong("fetched_at"),
                    hourlyRate = j.getDouble("hourly_rate"),
                    blocks = blocks
                )
                ScheduleStore.write(applicationContext, schedule)
                MoneyTickerWidgetProvider.requestUpdate(applicationContext)
                MoneyTickerWidgetProvider.schedulePartialTick(applicationContext, schedule)
                reconcileTickerService(schedule)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun reconcileTickerService(schedule: Schedule) {
        val now = System.currentTimeMillis() / 1000
        val active = schedule.blocks.any { it.start <= now && now < it.end }
        if (active) {
            WidgetTickerService.start(applicationContext)
        } else {
            WidgetTickerService.stop(applicationContext)
        }
    }
}
