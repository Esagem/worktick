package dev.surge.worktick

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class WorkTickApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val req = PeriodicWorkRequestBuilder<ScheduleFetchWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "worktick_fetch", ExistingPeriodicWorkPolicy.KEEP, req
        )

        BlockBoundaryScheduler.scheduleNext(this)
    }
}

object BlockBoundaryScheduler {
    private const val REQ_CODE = 7777

    fun scheduleNext(context: Context) {
        val schedule = ScheduleStore.read(context) ?: return
        val now = System.currentTimeMillis() / 1000
        val starts = schedule.blocks.map { it.start }.filter { it > now }
        val ends = schedule.blocks.map { it.end }.filter { it > now }
        val nextBoundary = (starts + ends).minOrNull() ?: return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, REQ_CODE,
            Intent(context, BlockBoundaryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextBoundary * 1000L, pi)
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, nextBoundary * 1000L, pi)
        }
    }
}
