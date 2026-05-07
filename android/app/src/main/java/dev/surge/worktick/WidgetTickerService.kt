package dev.surge.worktick

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

class WidgetTickerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var displayManager: DisplayManager

    // Diagnostic counters — reset on service start, logged on stop. Lets us
    // verify post-fix that screen-off ticks really are zero. All increments
    // happen on the main thread (handler runs there), so no atomic needed.
    private var renderedTicks: Long = 0L
    private var skippedKeyguard: Long = 0L
    private var skippedDisplayOff: Long = 0L

    /**
     * "Is the user actively looking at the phone right now?"
     *
     * `Display.STATE_ON` (not `PowerManager.isInteractive()`) is the canonical
     * "fully-on display" signal. AOD maps to `STATE_DOZE`/`STATE_DOZE_SUSPEND`,
     * which we want to skip — `isInteractive()` returns true on AOD on Samsung,
     * which previously caused the widget to keep ticking through AOD updates and
     * burn far more battery than expected. `isKeyguardLocked()` then catches the
     * lock-screen-with-screen-on case (user briefly woke phone but didn't unlock).
     */
    private fun isUserLooking(): Boolean {
        if (keyguardManager.isKeyguardLocked) return false
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        return display.state == Display.STATE_ON
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Each of these can transition us from "not looking" to "maybe looking"
                // — kick off an immediate evaluation. The tick runnable itself decides
                // whether to actually render based on isUserLooking().
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    handler.removeCallbacks(tick)
                    handler.post(tick)
                }
                // No SCREEN_OFF handler needed — the next scheduled tick will check
                // isUserLooking(), see false, and stop re-posting itself.
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        ensureChannel()
        renderedTicks = 0L
        skippedKeyguard = 0L
        skippedDisplayOff = 0L
        isRunning = true
        // Service is now the canonical update source — silence the AlarmManager-based
        // fallback so the device doesn't get woken every cent boundary by both paths.
        MoneyTickerWidgetProvider.cancelTicking(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            // SCREEN_ON catches the "phone-was-already-unlocked, briefly-locked-by-power-button,
            // power-button-pressed-again-to-wake" path that USER_PRESENT misses. Spurious
            // SCREEN_ON during Samsung AOD is harmless — the tick handler's STATE_ON check
            // filters those without rendering.
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildSilentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        isRunning = false
        Log.i(TAG,
            "stopped: rendered=$renderedTicks " +
            "skipped(keyguard)=$skippedKeyguard skipped(display-off)=$skippedDisplayOff")
        // If we're stopping but a block is still active, hand back to the AlarmManager
        // path so the widget stays live (just less smoothly).
        ScheduleStore.read(this)?.let { schedule ->
            val now = System.currentTimeMillis() / 1000
            if (schedule.blocks.any { it.start <= now && now < it.end }) {
                MoneyTickerWidgetProvider.schedulePartialTick(this, schedule)
            }
        }
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            val schedule = ScheduleStore.read(this@WidgetTickerService)
            if (schedule == null || !hasActiveBlock(schedule) || !hasWidgetInstance()) {
                // Block ended, schedule cleared, or widget was removed from the
                // home screen. Stop the FGS so we're not burning power for nothing.
                stopSelf()
                return
            }
            // Two-stage filter so we can attribute skipped ticks to the right cause
            // in the diagnostic log.
            if (keyguardManager.isKeyguardLocked) {
                skippedKeyguard++
                return
            }
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            if (display == null || display.state != Display.STATE_ON) {
                skippedDisplayOff++
                return
            }
            renderedTicks++
            tickWidget()
            handler.postDelayed(this, MoneyTickerWidgetProvider.nextCentTickMs(schedule))
        }
    }

    private fun hasActiveBlock(schedule: Schedule): Boolean {
        val now = System.currentTimeMillis() / 1000
        return schedule.blocks.any { it.start <= now && now < it.end }
    }

    private fun hasWidgetInstance(): Boolean {
        val mgr = AppWidgetManager.getInstance(this)
        return mgr.getAppWidgetIds(ComponentName(this, MoneyTickerWidgetProvider::class.java)).isNotEmpty()
    }

    private fun tickWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, MoneyTickerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = RemoteViews(packageName, R.layout.worktick_money)
        MoneyTickerWidgetProvider.applyWidgetData(this, views)
        for (id in ids) {
            mgr.partiallyUpdateAppWidget(id, views)
        }
    }

    private fun buildSilentNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WorkTick")
            .setContentText("Updating widget")
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Widget updater",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Powers the live home-screen widget"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "WidgetTickerService"
        private const val CHANNEL_ID = "worktick_widget_updater"
        private const val NOTIF_ID = 4243

        /** Read by MoneyTickerWidgetProvider so the alarm-based ticker doesn't double-up
         *  with the FGS handler. Safe to read without a lock — single writer (this service
         *  on its main thread) and readers tolerate stale values. */
        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WidgetTickerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WidgetTickerService::class.java))
        }
    }
}
