package dev.surge.worktick

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class BlockBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val schedule = ScheduleStore.read(context) ?: return
        val now = System.currentTimeMillis() / 1000
        val active = schedule.blocks.any { it.start <= now && now < it.end }

        // Render the new state immediately. Without this, the ON→OFF transition
        // would just call stopService() and the widget would freeze on the last
        // ON CLOCK frame until something else redraws it — the FGS's onDestroy
        // cancels the tick runnable before its final-render path can run.
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(
            ComponentName(context, MoneyTickerWidgetProvider::class.java)
        )
        if (ids.isNotEmpty()) {
            val views = RemoteViews(context.packageName, R.layout.worktick_money)
            MoneyTickerWidgetProvider.applyWidgetData(context, views)
            for (id in ids) mgr.partiallyUpdateAppWidget(id, views)
        }

        if (active) {
            WidgetTickerService.start(context)
        } else {
            WidgetTickerService.stop(context)
        }
        BlockBoundaryScheduler.scheduleNext(context)
    }
}
