package dev.surge.worktick

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

/**
 * Fires a heads-up notification when it's time to clock in or out. Tapping the
 * notification launches the user's portal URL (set in Settings). Each ping is
 * scheduled as an exact alarm at `boundary - leadMinutes`. After firing, the
 * receiver re-schedules the next ping so the chain keeps itself going.
 *
 * State lives entirely in the cached schedule + WTSettings — no extra storage.
 */
class ClockReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val kind = intent?.getStringExtra(EXTRA_KIND) ?: KIND_IN
        ClockReminderNotifier.show(context, kind)
        // Chain: schedule the next reminder so the alarm pipeline keeps flowing.
        ClockReminderScheduler.scheduleNext(context)
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val KIND_IN = "in"
        const val KIND_OUT = "out"
    }
}

object ClockReminderScheduler {
    private const val REQ_CODE = 7801
    private const val TAG = "ClockReminderScheduler"

    /**
     * Looks at the cached schedule + lead-minute setting, finds the next
     * upcoming boundary that hasn't been pinged yet, and sets a single exact
     * alarm for it. Called from app start, after every poll, after every
     * settings change that affects timing, and from the receiver itself.
     */
    fun scheduleNext(context: Context) {
        val am = context.getSystemService<AlarmManager>() ?: return
        val pi = pendingIntent(context, ClockReminderReceiver.KIND_IN, update = true)
        am.cancel(pi)

        val schedule = ScheduleStore.read(context) ?: return
        val leadSec = WTSettings.notifyLeadMinutes(context) * 60L
        val nowSec = System.currentTimeMillis() / 1000

        // Build (fireAt, kind) pairs for all upcoming start/end boundaries, then
        // pick the earliest. We schedule one at a time and re-schedule after
        // each fire — keeps things simple and avoids stale slots if the
        // schedule or lead-time changes.
        data class Slot(val fireAt: Long, val kind: String)
        val slots = mutableListOf<Slot>()
        for (b in schedule.blocks) {
            val inFire = b.start - leadSec
            val outFire = b.end - leadSec
            if (inFire > nowSec) slots += Slot(inFire, ClockReminderReceiver.KIND_IN)
            if (outFire > nowSec) slots += Slot(outFire, ClockReminderReceiver.KIND_OUT)
        }
        val next = slots.minByOrNull { it.fireAt } ?: return

        val firePi = pendingIntent(context, next.kind, update = true)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.fireAt * 1000L, firePi)
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, next.fireAt * 1000L, firePi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService<AlarmManager>() ?: return
        // Same REQ_CODE for both kinds (we only ever have one pending) — cancel by either.
        am.cancel(pendingIntent(context, ClockReminderReceiver.KIND_IN, update = true))
    }

    private fun pendingIntent(context: Context, kind: String, update: Boolean): PendingIntent {
        val intent = Intent(context, ClockReminderReceiver::class.java).apply {
            putExtra(ClockReminderReceiver.EXTRA_KIND, kind)
        }
        // FLAG_UPDATE_CURRENT so the latest extras stick on re-schedule. Same
        // REQ_CODE for both kinds — there's only ever one pending reminder.
        val flags = (if (update) PendingIntent.FLAG_UPDATE_CURRENT else 0) or
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQ_CODE, intent, flags)
    }
}

object ClockReminderNotifier {
    const val CHANNEL_ID = "worktick_clock_reminders"
    private const val NOTIFICATION_ID = 7802

    fun show(context: Context, kind: String) {
        ensureChannel(context)

        val portalUrl = WTSettings.portalUrl(context)
        val tapIntent: PendingIntent? = if (portalUrl.isNotBlank() && portalUrl.looksLikeUrl()) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(portalUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                context, 0, view,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            // No portal configured — tap opens the app's main screen so the user
            // can configure it (and at least see their shift status).
            val main = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            PendingIntent.getActivity(
                context, 0, main,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val title: String
        val body: String
        if (kind == ClockReminderReceiver.KIND_OUT) {
            title = "Time to clock out"
            body = if (portalUrl.isBlank()) "Set your portal URL in WorkTick → Settings."
                   else "Tap to open your portal and clock out."
        } else {
            title = "Time to clock in"
            body = if (portalUrl.isBlank()) "Set your portal URL in WorkTick → Settings."
                   else "Tap to open your portal and clock in."
        }

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()

        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Clock-in / Clock-out reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Pings at the start and end of each work block."
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun String.looksLikeUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}
