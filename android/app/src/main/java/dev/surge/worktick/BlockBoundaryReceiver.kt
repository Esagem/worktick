package dev.surge.worktick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BlockBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val schedule = ScheduleStore.read(context) ?: return
        val now = System.currentTimeMillis() / 1000
        val active = schedule.blocks.any { it.start <= now && now < it.end }
        if (active) {
            WidgetTickerService.start(context)
        } else {
            WidgetTickerService.stop(context)
        }
        BlockBoundaryScheduler.scheduleNext(context)
    }
}
