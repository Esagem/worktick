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
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
        // Last widget instance was removed. Drop the foreground ticker too —
        // it has nothing to update and would otherwise burn battery for nothing.
        WidgetTickerService.stop(context)
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

        val openIntent = Intent(context, MainActivity::class.java).apply {
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

    /**
     * Paint instances that get reused across every renderTerminalBitmap call.
     * Avoids ~per-tick GC churn from the small-but-frequent allocations the canvas
     * paints used to spawn (notably scanPaint, which gets used ~30× per render).
     * Companion-scoped because rendering is synchronized on the main thread —
     * Provider.onReceive and WidgetTickerService.tick can't run concurrently.
     */
    private object SharedPaints {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
        val scan = Paint().apply { color = Color.argb(5, 255, 255, 255) }
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
            // Foreground service is the canonical update source when running. Skip the
            // alarm path so the device isn't woken every cent boundary by both — major
            // battery saving during active blocks.
            if (!active || WidgetTickerService.isRunning) {
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

        // ─────────────────── Tier 1 render caches ───────────────────
        // The static layer (bg + border + scanlines + rule caps + track) is identical
        // for every tick within a state, so we render it once into a per-state cached
        // bitmap and stamp it onto each tick's output. The output bitmap itself is
        // double-buffered to eliminate the ~500 KB native-heap alloc per render.
        // Scratch Rects + FontMetrics are reused so every tick doesn't allocate them.

        private const val CANVAS_W = 720
        private const val CANVAS_H = 176

        private val staticLayerCache = mutableMapOf<State, Bitmap>()
        private var outputBufferA: Bitmap? = null
        private var outputBufferB: Bitmap? = null
        private var nextBufferIsA: Boolean = true

        private val scratchRectF = RectF()
        private val scratchTextRect1 = Rect()
        private val scratchTextRect2 = Rect()
        private val scratchTextRect3 = Rect()
        private val scratchFontMetrics = Paint.FontMetrics()

        // PorterDuff.SRC for the static-layer blit: overwrite destination pixels
        // outright instead of doing per-pixel alpha blending. Cuts a 504 KB
        // SRC_OVER blend down to a flat 504 KB memcpy. Companion-level constant
        // so we don't allocate a Paint per render.
        private val staticLayerCopyPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }

        private var monoTypeface: Typeface? = null
        private fun mono(context: Context): Typeface =
            monoTypeface ?: (
                ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold)
                    ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            ).also { monoTypeface = it }

        private fun obtainOutputBitmap(): Bitmap {
            val current = if (nextBufferIsA) outputBufferA else outputBufferB
            val bm = if (current == null || current.isRecycled ||
                         current.width != CANVAS_W || current.height != CANVAS_H) {
                Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888).also {
                    if (nextBufferIsA) outputBufferA = it else outputBufferB = it
                }
            } else {
                // No eraseColor needed — the static-layer drawBitmap below uses
                // PorterDuff.SRC mode which overwrites every pixel, so the buffer's
                // previous contents are irrelevant.
                current
            }
            nextBufferIsA = !nextBufferIsA
            return bm
        }

        private fun obtainStaticLayer(state: State): Bitmap {
            staticLayerCache[state]?.takeIf { !it.isRecycled }?.let { return it }
            val bm = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888)
            drawStaticLayer(Canvas(bm), state)
            staticLayerCache[state] = bm
            return bm
        }

        private fun drawStaticLayer(canvas: Canvas, state: State) {
            val accentDim = when (state) {
                State.ON   -> Color.parseColor("#1A4A30")
                State.SYNC -> Color.parseColor("#5A3F0A")
                State.OFF  -> Color.parseColor("#4A1822")
            }
            val bg = Color.parseColor("#0B0D10")
            val rule = Color.parseColor("#1A1D22")
            val w = CANVAS_W.toFloat()
            val h = CANVAS_H.toFloat()
            val radius = 36f
            val padX = 36f
            val ruleY = h - 24f
            val capH = 14f

            SharedPaints.bg.color = bg
            scratchRectF.set(0f, 0f, w, h)
            canvas.drawRoundRect(scratchRectF, radius, radius, SharedPaints.bg)

            SharedPaints.border.color = accentDim
            val inset = 1f
            scratchRectF.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(scratchRectF, radius - inset, radius - inset, SharedPaints.border)

            var y = 0
            while (y < CANVAS_H) {
                canvas.drawRect(0f, y.toFloat(), w, y + 1f, SharedPaints.scan)
                y += 6
            }

            // Rule caps + track. The fill (variable width) is drawn dynamically per tick.
            val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentDim; strokeWidth = 2f }
            canvas.drawLine(padX, ruleY - capH / 2, padX, ruleY + capH / 2, capPaint)
            canvas.drawLine(w - padX, ruleY - capH / 2, w - padX, ruleY + capH / 2, capPaint)

            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rule; strokeWidth = 2f }
            canvas.drawLine(padX + 1f, ruleY, w - padX - 1f, ruleY, trackPaint)
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
         *
         * @Synchronized because callers come from multiple threads (FGS handler,
         * provider broadcast, WorkManager worker). The shared output buffers and
         * static-layer cache need single-writer access.
         */
        @Synchronized
        fun renderTerminalBitmap(
            context: Context,
            state: State,
            moneyText: String,
            totalHours: Double,
            shiftHours: Double,
            plannedHours: Double,
            rate: Double
        ): Bitmap {
            val output = obtainOutputBitmap()
            val canvas = Canvas(output)

            // Stamp the cached static layer. Replaces ~30 scanline drawRect calls,
            // 2 roundRect calls, and 3 rule drawLines that used to run every tick.
            // PorterDuff.SRC mode → flat memcpy, no per-pixel alpha blend.
            canvas.drawBitmap(obtainStaticLayer(state), 0f, 0f, staticLayerCopyPaint)

            val w = CANVAS_W
            val h = CANVAS_H

            // Per-render dynamic palette (only the colors we still need to draw with)
            val accent: Int
            val statusLabel: String
            when (state) {
                State.ON   -> { accent = Color.parseColor("#3DFF9A"); statusLabel = "ON CLOCK" }
                State.SYNC -> { accent = Color.parseColor("#FFB22C"); statusLabel = "SYNC" }
                State.OFF  -> { accent = Color.parseColor("#FF4D5C"); statusLabel = "OFF DUTY" }
            }
            val white = Color.parseColor("#FFFFFF")
            val mid = Color.parseColor("#8A8F97")
            val dim = Color.parseColor("#7A8089")
            val label = Color.parseColor("#CFD3DA")

            val typeface = mono(context)

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
                this.typeface = typeface; color = accent; textSize = 22f; isFakeBoldText = true
                letterSpacing = 0.16f
            }
            statusPaint.getFontMetrics(scratchFontMetrics)
            val statusBaselineY = statusY - (scratchFontMetrics.ascent + scratchFontMetrics.descent) / 2f
            canvas.drawText(statusLabel, padX + 28f, statusBaselineY, statusPaint)

            // TOP-RIGHT: all-time total + rate, right-aligned, mirrors status row
            val topLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface; color = label; textSize = 20f; isFakeBoldText = true
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
            topLabelPaint.getFontMetrics(scratchFontMetrics)
            val topBaseline = statusY - (scratchFontMetrics.ascent + scratchFontMetrics.descent) / 2f
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
                this.typeface = typeface; color = white; textSize = 76f
                isFakeBoldText = true; letterSpacing = -0.03f
                try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
            }
            val decPaint = Paint(wholePaint).apply { color = mid; textSize = 40f }
            val dollarPaint = Paint(wholePaint).apply { color = accent; textSize = 26f }

            wholePaint.getTextBounds(whole, 0, whole.length, scratchTextRect1)
            decPaint.getTextBounds(dec, 0, dec.length, scratchTextRect2)
            dollarPaint.getTextBounds("$", 0, 1, scratchTextRect3)

            // Auto-shrink if the money would collide with the left status block
            val rightEdge = w - padX
            val totalMoneyW = scratchTextRect3.width() + 4f + scratchTextRect1.width() + scratchTextRect2.width()
            val maxMoneyW = w * 0.62f
            if (totalMoneyW > maxMoneyW) {
                val k = maxMoneyW / totalMoneyW
                wholePaint.textSize *= k; decPaint.textSize *= k; dollarPaint.textSize *= k
                wholePaint.getTextBounds(whole, 0, whole.length, scratchTextRect1)
                decPaint.getTextBounds(dec, 0, dec.length, scratchTextRect2)
                dollarPaint.getTextBounds("$", 0, 1, scratchTextRect3)
            }

            // Baseline that puts the visible center of the digits at canvas center,
            // then nudge lower so the hero sits visually below the optical center.
            val moneyY = h / 2f - scratchTextRect1.exactCenterY() + 12f

            var cursor = rightEdge
            canvas.drawText(dec, cursor - scratchTextRect2.width(), moneyY, decPaint)
            cursor -= scratchTextRect2.width()
            canvas.drawText(whole, cursor - scratchTextRect1.width(), moneyY, wholePaint)
            cursor -= scratchTextRect1.width() + 4f
            canvas.drawText(
                "$",
                cursor - scratchTextRect3.width(),
                moneyY - scratchTextRect1.height() * 0.55f,
                dollarPaint
            )

            // BOTTOM-LEFT: shift / planned (caps + track come from cached static layer)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface; color = label; textSize = 20f
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

            // Bar fill ONLY (caps + track come from cached static layer)
            val ruleY = h - 24f
            val ruleLeft = padX
            val ruleRight = w - padX
            val pct = (shiftHours / plannedHours).coerceIn(0.0, 1.0)
            val fillEnd = ruleLeft + 1f + (ruleRight - ruleLeft - 2f) * pct.toFloat()
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 2f }
            if (state == State.ON) {
                fillPaint.setShadowLayer(6f, 0f, 0f, accent)
            }
            canvas.drawLine(ruleLeft + 1f, ruleY, fillEnd, ruleY, fillPaint)

            return output
        }
    }
}
