package dev.surge.worktick

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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

class WidgetTickerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager
    private var screenOff = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Pause ticking until the screen comes back on. The current tick's
                    // re-post in the runnable will see screenOff=true and skip.
                    screenOff = true
                }
                Intent.ACTION_SCREEN_ON -> if (screenOff) {
                    screenOff = false
                    // Catch-up tick immediately so the user sees the current value the
                    // moment they look at the phone, then resume the cent-aligned cadence.
                    handler.removeCallbacks(tick)
                    handler.post(tick)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
        isRunning = true
        // Service is now the canonical update source — silence the AlarmManager-based
        // fallback so the device doesn't get woken every cent boundary by both paths.
        MoneyTickerWidgetProvider.cancelTicking(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
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
            tickWidget()
            if (screenOff) {
                // Screen is off — no eyes on the widget. Don't re-post; ACTION_SCREEN_ON
                // will re-arm us. The single tick we just did keeps the widget current
                // for AOD / pull-down-shade peeks until then.
                return
            }
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
