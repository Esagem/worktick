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

/**
 * Live money-ticker widget — big number is all-time gross dollars.
 *
 * - When NO block is active: static value, system updates every 30 min via providerInfo.
 * - When a block IS active: AlarmManager schedules a re-render at a rate-derived interval.
 *   See `tickIntervalMs(hourlyRate)`. Tick rate scales with hourly rate so we always
 *   hit at least 2 ticks per penny:
 *     $15/hr  → penny / 2.4s  → tick every 1.000s (1.0 Hz, clamped from 1.2s)
 *     $30/hr  → penny / 1.2s  → tick every 0.600s (1.7 Hz)
 *     $60/hr  → penny / 0.6s  → tick every 0.300s (3.3 Hz)
 *     $100/hr → penny / 0.36s → tick every 0.250s (4.0 Hz, floor)
 */
class MoneyTickerWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ScheduleStore.read(context)?.let { maybeStartTicking(context, it) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelTicking(context)
    }

    override fun onUpdate(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) render(context, appWidgetManager, id)
        ScheduleStore.read(context)?.let { maybeStartTicking(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK, ACTION_REFRESH -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, MoneyTickerWidgetProvider::class.java)
                )
                for (id in ids) render(context, mgr, id)
                if (intent.action == ACTION_TICK) {
                    ScheduleStore.read(context)?.let { maybeStartTicking(context, it) }
                }
            }
        }
    }

    private fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.worktick_money)
        val schedule = ScheduleStore.read(context)

        if (schedule == null) {
            views.setTextViewText(R.id.wt_status, "Loading…")
            views.setTextViewText(R.id.wt_money, "$0.00")
            views.setTextViewText(R.id.wt_subline, "Waiting on backend")
            mgr.updateAppWidget(widgetId, views)
            return
        }

        val now = System.currentTimeMillis() / 1000
        val allTime = Math.allTime(schedule.blocks, now)
        val rate = schedule.hourlyRate
        val isActive = allTime.activeStart != null

        views.setTextViewText(
            R.id.wt_status,
            if (isActive) "● Working · All-time gross" else "○ Off · All-time gross"
        )
        views.setTextViewText(
            R.id.wt_money,
            formatMoney(allTime.totalDollars(now, rate))
        )
        val totalHours = allTime.totalSeconds(now) / 3600.0
        views.setTextViewText(
            R.id.wt_subline,
            "%.1fh @ %s/h".format(totalHours, formatMoney(rate))
        )

        val tapIntent = Intent(context, MoneyTickerWidgetProvider::class.java).apply { action = ACTION_REFRESH }
        val tapPi = PendingIntent.getBroadcast(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.wt_root, tapPi)

        mgr.updateAppWidget(widgetId, views)
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

        fun maybeStartTicking(context: Context, schedule: Schedule) {
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
            // Tick interval scales with hourly rate so we update twice per penny.
            // Clamped to [250 ms, 1000 ms] — faster wastes battery, slower misses pennies.
            val intervalMs = tickIntervalMs(schedule.hourlyRate)
            val triggerAt = SystemClock.elapsedRealtime() + intervalMs
            try {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } catch (e: SecurityException) {
                am.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pi)
            }
        }

        /** Derive ideal tick interval (ms) from hourly rate.
         *
         *  Math: at `rate` $/hr, pennies accrue at rate*100 per hour, so
         *  seconds-per-penny = 3600 / (rate*100) = 36 / rate.
         *  We tick twice per penny for fluidity, then clamp to [250 ms, 1000 ms]. */
        fun tickIntervalMs(hourlyRate: Double): Long {
            if (hourlyRate <= 0) return 1000L
            val secondsPerPenny = 36.0 / hourlyRate
            val ideal = secondsPerPenny / 2.0
            return (ideal.coerceIn(0.25, 1.0) * 1000).toLong()
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

        private fun formatMoney(amount: Double): String {
            val fmt = NumberFormat.getCurrencyInstance(Locale.US)
            fmt.maximumFractionDigits = 2
            fmt.minimumFractionDigits = 2
            return fmt.format(amount)
        }
    }
}
