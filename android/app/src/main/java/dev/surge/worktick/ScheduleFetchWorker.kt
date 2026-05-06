package dev.surge.worktick

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic worker that fetches /schedule and persists it.
 *
 * Reuses BackendClient for the fetch, then reconciles WidgetTickerService —
 * starts it if a block is currently active, stops it otherwise.
 */
class ScheduleFetchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val schedule = BackendClient.fetchSchedule(applicationContext)
            MoneyTickerWidgetProvider.schedulePartialTick(applicationContext, schedule)
            reconcileTickerService(schedule)
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
