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

/** Backend connection config — set these or wire to BuildConfig. */
object WTConfig {
    const val BACKEND_URL = "https://YOUR-APP.fly.dev"
    const val API_SECRET = "PASTE_API_SHARED_SECRET_HERE"
}

/**
 * Periodic worker that fetches /schedule and persists it.
 * Default schedule is every 6 hours (set in WorkTickApp).
 */
class ScheduleFetchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${WTConfig.BACKEND_URL}/schedule")
                .header("Authorization", "Bearer ${WTConfig.API_SECRET}")
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
                MoneyTickerWidgetProvider.maybeStartTicking(applicationContext, schedule)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
