package dev.surge.worktick

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * WorkTick "Terminal" widget — 4×1 horizontal layout.
 *
 * The whole face is drawn as a single Bitmap into one ImageView, because
 * RemoteViews can't render corner brackets, capped rules, or letter-spaced
 * mono text the way the design needs.
 *
 *   - Money: tabular-nums, white, accent-colored "$" and ".cc" tail
 *   - Status pill: top-left dot + label
 *   - Bottom: shift progress bar with end-caps; fill = shiftHours / plannedHours
 *   - States: ON (green), OFF (grey), SYNC (red)
 */
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
        applyWidgetData(context, views)

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
        applyWidgetData(context, views)
        for (id in ids) {
            mgr.partiallyUpdateAppWidget(id, views)
        }
    }

    enum class State { ON, OFF, SYNC }

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

        /**
         * Milliseconds until the next whole-cent flip on the displayed total.
         *
         * Each cent earned takes `periodMs = 36000 / rate` ms; we figure out where we
         * are in the current period and schedule for the very next boundary, plus a
         * slack offset so we always land on the late side. The slack matters because
         * Handler.postDelayed schedules in uptimeMillis but we compute deltas in
         * currentTimeMillis — those clocks drift by a few ms, and without enough
         * cushion the Handler occasionally fires *before* the boundary, re-rendering
         * the same cent and creating a perceived freeze. 50ms is well above measured
         * Handler jitter without making short periods (high rate) feel laggy.
         */
        fun nextCentTickMs(schedule: Schedule, nowMs: Long = System.currentTimeMillis()): Long {
            val rate = schedule.hourlyRate
            if (rate <= 0) return 1000L
            val nowSec = nowMs / 1000
            val computed = Math.allTime(schedule.blocks, nowSec)
            val activeStart = computed.activeStart ?: return 1000L

            val totalMs = computed.completedSeconds * 1000L + (nowMs - activeStart * 1000L)
            val periodMs = 36000.0 / rate
            val centsNow = (totalMs / periodMs).toLong()
            val nextCentMs = ((centsNow + 1) * periodMs).toLong()
            // Cap slack so it never exceeds a quarter of the period (matters at high rates).
            val slack = minOf(50L, (periodMs / 4).toLong()).coerceAtLeast(5L)
            val delay = nextCentMs - totalMs + slack
            return delay.coerceIn(50L, 2000L)
        }

        /**
         * FLOOR rounding so $60.01 displays exactly when totalDollars >= 60.010 — the
         * same boundary nextCentTickMs schedules ticks for. With default HALF_UP, the
         * display flipped at 60.005 (600ms early at $30/hr), putting our ticks half a
         * period out of phase with the visual update.
         */
        private fun formatMoney(amount: Double): String {
            val fmt = NumberFormat.getCurrencyInstance(Locale.US)
            fmt.maximumFractionDigits = 2
            fmt.minimumFractionDigits = 2
            fmt.roundingMode = RoundingMode.FLOOR
            return fmt.format(amount)
        }

        /** Hours expressed as H:MM (so 2.5 hours -> "2:30", not "2.50"). */
        private fun formatHM(hours: Double): String {
            val totalMinutes = (hours * 60).toLong().coerceAtLeast(0)
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            return "%d:%02d".format(h, m)
        }

        /**
         * Decide which state the widget is in and feed the canvas renderer.
         * Shared by the provider and WidgetTickerService.
         */
        fun applyWidgetData(context: Context, views: RemoteViews) {
            val schedule = ScheduleStore.read(context)
            if (schedule == null) {
                views.setImageViewBitmap(
                    R.id.wt_canvas,
                    renderTerminalBitmap(
                        context,
                        state = State.SYNC,
                        moneyText = "$0.00",
                        totalHours = 0.0,
                        shiftHours = 0.0,
                        plannedHours = 8.0,
                        rate = 0.0
                    )
                )
                return
            }
            val nowMs = System.currentTimeMillis()
            val now = nowMs / 1000
            val computed = Math.allTime(schedule.blocks, now)
            val rate = schedule.hourlyRate
            val isActive = computed.activeStart != null
            val state = if (isActive) State.ON else State.OFF

            // Keep the foreground ticker in sync with the active state. Idempotent —
            // safe to call repeatedly; covers mid-block widget installs and post-fetch
            // schedule updates that don't cross a block boundary.
            try {
                if (isActive) WidgetTickerService.start(context)
                else WidgetTickerService.stop(context)
            } catch (_: Throwable) { /* background-start restrictions: alarm fallback is fine */ }

            val total = computed.totalDollarsMs(nowMs, rate)
            val totalHours = computed.totalSeconds(now) / 3600.0
            val todayStart = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toEpochSecond()
            val shiftHours = computed.currentShiftSeconds(now, todayStart) / 3600.0
            val plannedHours = schedule.plannedShiftHours.coerceAtLeast(0.5)

            views.setImageViewBitmap(
                R.id.wt_canvas,
                renderTerminalBitmap(
                    context,
                    state = state,
                    moneyText = formatMoney(total),
                    totalHours = totalHours,
                    shiftHours = shiftHours,
                    plannedHours = plannedHours,
                    rate = rate
                )
            )
        }

        /**
         * Draw the full Terminal widget face onto a 720×176 bitmap.
         * (4×1 medium widget @ ~2× density. fitXY scales it to the host cell.)
         */
        fun renderTerminalBitmap(
            context: Context,
            state: State,
            moneyText: String,
            totalHours: Double,
            shiftHours: Double,
            plannedHours: Double,
            rate: Double
        ): Bitmap {
            val w = 720
            val h = 176
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bm)

            // Palette
            val accent: Int
            val accentDim: Int
            val statusLabel: String
            when (state) {
                State.ON   -> { accent = Color.parseColor("#3DFF9A"); accentDim = Color.parseColor("#1A4A30"); statusLabel = "ON CLOCK" }
                State.SYNC -> { accent = Color.parseColor("#FFB22C"); accentDim = Color.parseColor("#5A3F0A"); statusLabel = "SYNC" }
                State.OFF  -> { accent = Color.parseColor("#FF4D5C"); accentDim = Color.parseColor("#4A1822"); statusLabel = "OFF DUTY" }
            }
            val white = Color.parseColor("#FFFFFF")
            val mid = Color.parseColor("#8A8F97")
            val dim = Color.parseColor("#7A8089")
            val label = Color.parseColor("#CFD3DA")
            val rule = Color.parseColor("#1A1D22")
            val bg = Color.parseColor("#0B0D10")

            // Background + thin accent-tinted border + scanlines
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; style = Paint.Style.FILL }
            val radius = 36f
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, bgPaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentDim; style = Paint.Style.STROKE; strokeWidth = 2.5f
            }
            val inset = 1f
            canvas.drawRoundRect(
                RectF(inset, inset, w - inset, h - inset),
                radius - inset, radius - inset,
                borderPaint
            )

            val scanPaint = Paint().apply { color = Color.argb(5, 255, 255, 255) }
            var y = 0
            while (y < h) { canvas.drawRect(0f, y.toFloat(), w.toFloat(), y + 1f, scanPaint); y += 6 }

            // Fonts
            val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold)
                ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

            // TOP-LEFT: status dot + label
            val padX = 36f
            val statusY = 36f
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            canvas.drawCircle(padX + 8f, statusY, 7f, dotPaint)
            if (state == State.ON) {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent; alpha = 90
                    maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawCircle(padX + 8f, statusY, 7f, glow)
            }
            val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = mono; color = accent; textSize = 22f; isFakeBoldText = true
                letterSpacing = 0.16f
            }
            val sm = Paint.FontMetrics().also { statusPaint.getFontMetrics(it) }
            val statusBaselineY = statusY - (sm.ascent + sm.descent) / 2f
            canvas.drawText(statusLabel, padX + 28f, statusBaselineY, statusPaint)

            // TOP-RIGHT: all-time total + rate, right-aligned, mirrors status row
            val topLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = mono; color = label; textSize = 20f; isFakeBoldText = true
                letterSpacing = 0.14f
            }
            val topSepPaint = Paint(topLabelPaint).apply { color = dim; isFakeBoldText = false }
            val topRatePaint = Paint(topLabelPaint).apply { color = accent }

            val totalText = "TOTAL ${formatHM(totalHours)}"
            val topSep = " · "
            val rateText = "$%.0f/HR".format(rate)
            val totalTextW = topLabelPaint.measureText(totalText)
            val topSepW = topSepPaint.measureText(topSep)
            val rateTextW = topRatePaint.measureText(rateText)
            val tm = Paint.FontMetrics().also { topLabelPaint.getFontMetrics(it) }
            val topBaseline = statusY - (tm.ascent + tm.descent) / 2f
            var trX = w - padX - (totalTextW + topSepW + rateTextW)
            canvas.drawText(totalText, trX, topBaseline, topLabelPaint)
            trX += totalTextW
            canvas.drawText(topSep, trX, topBaseline, topSepPaint)
            trX += topSepW
            canvas.drawText(rateText, trX, topBaseline, topRatePaint)

            // CENTER-RIGHT: money hero, right-aligned
            val raw = moneyText.removePrefix("$")
            val dotIdx = raw.indexOf('.')
            val whole = if (dotIdx > 0) raw.substring(0, dotIdx) else raw
            val dec = if (dotIdx > 0) raw.substring(dotIdx) else ""

            val wholePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = mono; color = white; textSize = 76f
                isFakeBoldText = true; letterSpacing = -0.03f
                try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
            }
            val decPaint = Paint(wholePaint).apply { color = mid; textSize = 40f }
            val dollarPaint = Paint(wholePaint).apply { color = accent; textSize = 26f }

            val wholeBounds = Rect()
            wholePaint.getTextBounds(whole, 0, whole.length, wholeBounds)
            val decBounds = Rect()
            decPaint.getTextBounds(dec, 0, dec.length, decBounds)
            val dollarBounds = Rect()
            dollarPaint.getTextBounds("$", 0, 1, dollarBounds)

            // Auto-shrink if the money would collide with the left status block
            val rightEdge = w - padX
            val totalW = dollarBounds.width() + 4f + wholeBounds.width() + decBounds.width()
            val maxMoneyW = w * 0.62f
            if (totalW > maxMoneyW) {
                val k = maxMoneyW / totalW
                wholePaint.textSize *= k; decPaint.textSize *= k; dollarPaint.textSize *= k
                wholePaint.getTextBounds(whole, 0, whole.length, wholeBounds)
                decPaint.getTextBounds(dec, 0, dec.length, decBounds)
                dollarPaint.getTextBounds("$", 0, 1, dollarBounds)
            }

            // Baseline that puts the visible center of "1,169" on the canvas center,
            // then nudge lower so the hero sits visually below the optical center.
            val moneyY = h / 2f - wholeBounds.exactCenterY() + 12f

            // Right-aligned: dec, then whole, then dollar
            var cursor = rightEdge
            canvas.drawText(dec, cursor - decBounds.width(), moneyY, decPaint)
            cursor -= decBounds.width()
            canvas.drawText(whole, cursor - wholeBounds.width(), moneyY, wholePaint)
            cursor -= wholeBounds.width() + 4f
            canvas.drawText("$", cursor - dollarBounds.width(), moneyY - wholeBounds.height() * 0.55f, dollarPaint)

            // BOTTOM-LEFT: shift / planned, capped rule below
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = mono; color = label; textSize = 20f
                isFakeBoldText = true; letterSpacing = 0.14f
            }
            val dimPaint = Paint(labelPaint).apply { color = dim; isFakeBoldText = false }

            val rowY = h - 36f
            val shiftText = "SHIFT ${formatHM(shiftHours)}"
            val slashSep = " / "
            val plannedText = formatHM(plannedHours)

            var x = padX
            canvas.drawText(shiftText, x, rowY, labelPaint)
            x += labelPaint.measureText(shiftText)
            canvas.drawText(slashSep, x, rowY, dimPaint)
            x += dimPaint.measureText(slashSep)
            canvas.drawText(plannedText, x, rowY, dimPaint)

            // Capped rule, full width
            val ruleY = h - 24f
            val ruleLeft = padX
            val ruleRight = w - padX
            val capH = 14f

            val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentDim; strokeWidth = 2f }
            canvas.drawLine(ruleLeft, ruleY - capH / 2, ruleLeft, ruleY + capH / 2, capPaint)
            canvas.drawLine(ruleRight, ruleY - capH / 2, ruleRight, ruleY + capH / 2, capPaint)

            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rule; strokeWidth = 2f }
            canvas.drawLine(ruleLeft + 1f, ruleY, ruleRight - 1f, ruleY, trackPaint)

            val pct = (shiftHours / plannedHours).coerceIn(0.0, 1.0)
            val fillEnd = ruleLeft + 1f + (ruleRight - ruleLeft - 2f) * pct.toFloat()
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 2f }
            if (state == State.ON) {
                fillPaint.setShadowLayer(6f, 0f, 0f, accent)
            }
            canvas.drawLine(ruleLeft + 1f, ruleY, fillEnd, ruleY, fillPaint)

            return bm
        }
    }
}
