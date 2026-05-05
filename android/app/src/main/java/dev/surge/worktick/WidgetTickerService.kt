package dev.surge.worktick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import java.text.NumberFormat
import java.util.Locale

class WidgetTickerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
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
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            val schedule = ScheduleStore.read(this@WidgetTickerService)
            if (schedule == null || !hasActiveBlock(schedule)) {
                stopSelf()
                return
            }
            tickWidget(schedule)
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private fun hasActiveBlock(schedule: Schedule): Boolean {
        val now = System.currentTimeMillis() / 1000
        return schedule.blocks.any { it.start <= now && now < it.end }
    }

    private fun tickWidget(schedule: Schedule) {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, MoneyTickerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        val computed = Math.allTime(schedule.blocks, now)
        val isActive = computed.activeStart != null
        val views = RemoteViews(packageName, R.layout.worktick_money).apply {
            if (isActive) {
                setTextViewText(R.id.wt_status, "ON THE CLOCK")
                setInt(R.id.wt_status, "setBackgroundResource", R.drawable.wt_pill_bg_active)
            } else {
                setTextViewText(R.id.wt_status, "OFF")
                setInt(R.id.wt_status, "setBackgroundResource", R.drawable.wt_pill_bg)
            }
            setTextViewText(R.id.wt_money, formatMoney(computed.totalDollars(now, schedule.hourlyRate)))
            val totalHours = computed.totalSeconds(now) / 3600.0
            setTextViewText(R.id.wt_subline, "%.2fh · %s/hr".format(totalHours, formatMoney(schedule.hourlyRate)))
        }
        for (id in ids) {
            mgr.partiallyUpdateAppWidget(id, views)
        }
    }

    private fun buildSilentNotification(): Notification {
        val openIntent = Intent(this, SmoothActivity::class.java).apply {
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

    private fun formatMoney(amount: Double): String {
        val fmt = NumberFormat.getCurrencyInstance(Locale.US)
        fmt.maximumFractionDigits = 2
        fmt.minimumFractionDigits = 2
        return fmt.format(amount)
    }

    companion object {
        private const val CHANNEL_ID = "worktick_widget_updater"
        private const val NOTIF_ID = 4243
        private const val TICK_INTERVAL_MS = 1000L

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
