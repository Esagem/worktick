package dev.surge.worktick

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import java.text.NumberFormat
import java.util.Locale

class MoneyTickerWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ScheduleStore.read(context)?.let { schedulePartialTick(context, it) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelTicking(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) renderFull(context, appWidgetManager, id)
        ScheduleStore.read(context)?.let { schedulePartialTick(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK -> {
                tick(context)
                ScheduleStore.read(context)?.let { schedulePartialTick(context, it) }
            }
            ACTION_REFRESH -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, MoneyTickerWidgetProvider::class.java)
                )
                for (id in ids) renderFull(context, mgr, id)
                ScheduleStore.read(context)?.let { schedulePartialTick(context, it) }
            }
        }
    }

    private fun renderFull(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.worktick_money)
        applyData(context, views)

        // Tap opens the smooth full-screen view
        val openIntent = Intent(context, SmoothActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.wt_root, openPi)

        mgr.updateAppWidget(widgetId, views)
    }

    private fun tick(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(
            ComponentName(context, MoneyTickerWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.worktick_money)
        applyData(context, views)
        for (id in ids) {
            mgr.partiallyUpdateAppWidget(id, views)
        }
    }

    private fun applyData(context: Context, views: RemoteViews) {
        val schedule = ScheduleStore.read(context)
        if (schedule == null) {
            views.setTextViewText(R.id.wt_status, "LOADING")
            views.setInt(R.id.wt_status, "setBackgroundResource", R.drawable.wt_pill_bg)
            views.setTextViewText(R.id.wt_money, "$0.00")
            views.setTextViewText(R.id.wt_subline, "Waiting on backend")
            return
        }
        val now = System.currentTimeMillis() / 1000
        val computed = Math.allTime(schedule.blocks, now)
        val rate = schedule.hourlyRate
        val isActive = computed.activeStart != null
        if (isActive) {
            views.setTextViewText(R.id.wt_status, "ON THE CLOCK")
            views.setInt(R.id.wt_status, "setBackgroundResource", R.drawable.wt_pill_bg_active)
        } else {
            views.setTextViewText(R.id.wt_status, "OFF")
            views.setInt(R.id.wt_status, "setBackgroundResource", R.drawable.wt_pill_bg)
        }
        views.setTextViewText(R.id.wt_money, formatMoney(computed.totalDollars(now, rate)))
        val totalHours = computed.totalSeconds(now) / 3600.0
        views.setTextViewText(
            R.id.wt_subline,
            "%.2fh · %s/hr".format(totalHours, formatMoney(rate))
        )
    }

    companion object {
        const val ACTION_TICK = "dev.surge.worktick.TICK"
        const val ACTION_REFRESH = "dev.surge.worktick.REFRESH"
        private const val TICK_REQUEST_CODE = 1001

        fun requestUpdate(context: Context) {
            context.sendBroadcast(Intent(context, MoneyTickerWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            })
        }

        fun schedulePartialTick(context: Context, schedule: Schedule) {
            val now = System.currentTimeMillis() / 1000
            val active = schedule.blocks.any { it.start <= now && now < it.end }
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, TICK_REQUEST_CODE,
                Intent(context, MoneyTickerWidgetProvider::class.java).apply { action = ACTION_TICK },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!active) {
                am.cancel(pi)
                return
            }
            val intervalMs = tickIntervalMs(schedule.hourlyRate)
            val triggerAt = SystemClock.elapsedRealtime() + intervalMs
            try {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } catch (e: SecurityException) {
                am.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pi)
            }
        }

        fun cancelTicking(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, TICK_REQUEST_CODE,
                Intent(context, MoneyTickerWidgetProvider::class.java).apply { action = ACTION_TICK },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }

        fun tickIntervalMs(hourlyRate: Double): Long {
            if (hourlyRate <= 0) return 1000L
            val secondsPerPenny = 36.0 / hourlyRate
            val ideal = secondsPerPenny / 2.0
            return (ideal.coerceIn(0.25, 1.0) * 1000).toLong()
        }

        private fun formatMoney(amount: Double): String {
            val fmt = NumberFormat.getCurrencyInstance(Locale.US)
            fmt.maximumFractionDigits = 2
            fmt.minimumFractionDigits = 2
            return fmt.format(amount)
        }
    }
}
