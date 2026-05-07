package dev.surge.worktick

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.surge.worktick.calendar.SchedulePoller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic worker that polls Google Calendar directly. Runs every 6 hours via
 * WorkManager (configured in WorkTickApp). After fetching, reconciles
 * WidgetTickerService — starts it if a block is currently active, stops it
 * otherwise.
 */
class ScheduleFetchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        when (val result = SchedulePoller(applicationContext).pollOnce()) {
            is SchedulePoller.Result.Ok -> {
                ScheduleStore.write(applicationContext, result.schedule)
                MoneyTickerWidgetProvider.requestUpdate(applicationContext)
                MoneyTickerWidgetProvider.schedulePartialTick(applicationContext, result.schedule)
                reconcileTickerService(result.schedule)
                Result.success()
            }
            SchedulePoller.Result.NotAuthenticated -> {
                Log.w(TAG, "Not authenticated; user must sign in again. Failing without retry.")
                Result.failure()
            }
            is SchedulePoller.Result.Error -> {
                Log.w(TAG, "Poll failed: ${result.message}; will retry")
                Result.retry()
            }
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

    companion object {
        private const val TAG = "ScheduleFetchWorker"

        /** Fire a one-shot fetch immediately. Used after Save/Sign-in. */
        fun runOnce(context: Context) {
            val req = OneTimeWorkRequestBuilder<ScheduleFetchWorker>().build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
