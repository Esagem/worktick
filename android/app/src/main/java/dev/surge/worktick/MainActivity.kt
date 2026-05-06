package dev.surge.worktick

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    // Hero refs
    private lateinit var heroCard: LinearLayout
    private lateinit var statusDot: DotView
    private lateinit var statusLabel: TextView
    private lateinit var statusRight: TextView
    private lateinit var heroMoney: TextView
    private lateinit var shiftLabel: TextView
    private lateinit var shiftBar: CappedBarView

    // Section content refs
    private lateinit var todayContent: LinearLayout
    private lateinit var weekContent: LinearLayout
    private lateinit var nextShiftContent: LinearLayout
    private lateinit var allTimeContent: LinearLayout
    private lateinit var fetchedLine: TextView

    // Title + sync refs
    private lateinit var titleText: TextView
    private lateinit var syncButton: TextView

    // Permissions refs
    private lateinit var batteryStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var allowBackgroundBtn: Button

    private var monoBold: Typeface? = null
    private var monoMedium: Typeface? = null

    private val moneyFmt: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(Locale.US).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 2
            roundingMode = java.math.RoundingMode.FLOOR
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monoBold = ResourcesCompat.getFont(this, R.font.jetbrains_mono_bold)
        monoMedium = ResourcesCompat.getFont(this, R.font.jetbrains_mono_medium)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // Foreground-state context: reconcile the ticker service against the current
        // schedule. Covers mid-block widget installs and any path where the boundary
        // alarm hasn't had a chance to fire yet.
        val schedule = ScheduleStore.read(this)
        if (schedule != null) {
            val now = System.currentTimeMillis() / 1000
            val active = schedule.blocks.any { it.start <= now && now < it.end }
            if (active) WidgetTickerService.start(this) else WidgetTickerService.stop(this)
        }
        MoneyTickerWidgetProvider.requestUpdate(this)

        promptOnFirstLaunchIfNeeded()
        renderEverything()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    /**
     * Hero-only refresh: just the money + shift label + bar. Self-paces using the
     * same cent-aligned scheduler the widget uses, so digits flip exactly when
     * a new cent is earned.
     */
    private val ticker = object : Runnable {
        override fun run() {
            val schedule = ScheduleStore.read(this@MainActivity)
            renderHero(schedule)
            val delay = if (schedule != null && schedule.blocks.any {
                val now = System.currentTimeMillis() / 1000; it.start <= now && now < it.end
            }) {
                MoneyTickerWidgetProvider.nextCentTickMs(schedule)
            } else 1000L
            handler.postDelayed(this, delay)
        }
    }

    // ───────────────────────── Render ─────────────────────────

    private fun renderEverything() {
        val schedule = ScheduleStore.read(this)
        renderTitle(schedule)
        renderHero(schedule)
        renderToday(schedule)
        renderWeek(schedule)
        renderNextShift(schedule)
        renderAllTime(schedule)
        renderFetched(schedule)
        renderPermissions()
    }

    private fun renderTitle(schedule: Schedule?) {
        val title = schedule?.workEventTitle?.takeIf { it.isNotBlank() } ?: "—"
        titleText.text = title
    }

    private fun renderHero(schedule: Schedule?) {
        val palette = palette(schedule)
        applyHeroBorder(palette.accentDim)
        statusDot.setColor(palette.accent, glow = palette.state == State.ON)
        statusLabel.text = palette.label
        statusLabel.setTextColor(palette.accent)

        if (schedule == null) {
            statusRight.text = ""
            heroMoney.text = "$0.00"
            shiftLabel.text = "SHIFT 0:00 / 8:00"
            shiftBar.pct = 0f
            shiftBar.accent = palette.accent
            shiftBar.accentDim = palette.accentDim
            return
        }

        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val computed = Math.allTime(schedule.blocks, nowSec)
        val rate = schedule.hourlyRate
        val totalHours = computed.totalSeconds(nowSec) / 3600.0

        statusRight.text = "T+${formatHM(totalHours)} · ${rateText(rate)}"
        statusRight.setTextColor(palette.accent)

        heroMoney.text = moneyFmt.format(computed.totalDollarsMs(nowMs, rate))

        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toEpochSecond()
        val shiftSeconds = computed.currentShiftSeconds(nowSec, todayStart)
        val planned = schedule.plannedShiftHours.coerceAtLeast(0.5)
        shiftLabel.text = "SHIFT ${formatHM(shiftSeconds / 3600.0)} / ${formatHM(planned)}"
        val pct = ((shiftSeconds / 3600.0) / planned).toFloat().coerceIn(0f, 1f)
        shiftBar.pct = pct
        shiftBar.accent = palette.accent
        shiftBar.accentDim = palette.accentDim
    }

    private fun renderToday(schedule: Schedule?) {
        todayContent.removeAllViews()
        if (schedule == null) {
            todayContent.addView(dimRow("No schedule cached"))
            return
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayBlocks = schedule.blocks.filter {
            val d = Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate()
            d == today
        }
        if (todayBlocks.isEmpty()) {
            todayContent.addView(dimRow("No shifts scheduled today"))
            return
        }
        val nowSec = System.currentTimeMillis() / 1000
        var totalSec = 0L
        for (b in todayBlocks) {
            val dur = b.end - b.start
            totalSec += dur
            val state = when {
                b.end <= nowSec -> "DONE"
                b.start <= nowSec && nowSec < b.end -> "NOW"
                else -> "QUEUED"
            }
            todayContent.addView(blockRow(b, dur, schedule.hourlyRate, state))
        }
        if (todayBlocks.size > 1) {
            todayContent.addView(totalRow(totalSec, schedule.hourlyRate))
        }
    }

    private fun renderWeek(schedule: Schedule?) {
        weekContent.removeAllViews()
        if (schedule == null) return

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monday = today.with(DayOfWeek.MONDAY)
        val weekStart = monday.atStartOfDay(zone).toEpochSecond()
        val weekEnd = monday.plusDays(7).atStartOfDay(zone).toEpochSecond()

        val byDay = (0..6).map { offset ->
            val d = monday.plusDays(offset.toLong())
            val dayStart = d.atStartOfDay(zone).toEpochSecond()
            val dayEnd = d.plusDays(1).atStartOfDay(zone).toEpochSecond()
            val seconds = schedule.blocks
                .filter { it.start >= dayStart && it.start < dayEnd }
                .sumOf { it.end - it.start }
            Triple(d, seconds, d == today)
        }

        val totalSec = byDay.sumOf { it.second }
        if (totalSec == 0L) {
            weekContent.addView(dimRow("No shifts this week"))
            return
        }
        val totalDollars = totalSec.toDouble() * schedule.hourlyRate / 3600.0
        weekContent.addView(weekTotalRow(totalSec, totalDollars))

        for ((d, seconds, isToday) in byDay) {
            weekContent.addView(weekDayRow(d, seconds, schedule.hourlyRate, isToday))
        }
    }

    private fun renderNextShift(schedule: Schedule?) {
        nextShiftContent.removeAllViews()
        if (schedule == null) {
            nextShiftContent.addView(dimRow("No schedule cached"))
            return
        }
        val nowSec = System.currentTimeMillis() / 1000
        val next = schedule.blocks.firstOrNull { it.start > nowSec }
        if (next == null) {
            nextShiftContent.addView(dimRow("No upcoming shifts"))
            return
        }
        val zone = ZoneId.systemDefault()
        val zdt = Instant.ofEpochSecond(next.start).atZone(zone)
        val dur = next.end - next.start
        val until = next.start - nowSec

        nextShiftContent.addView(infoRow(
            "${zdt.format(DateTimeFormatter.ofPattern("EEE MMM d"))} · ${zdt.format(DateTimeFormatter.ofPattern("HH:mm"))}",
            "${formatHM(dur / 3600.0)}",
            valueColor = "#CFD3DA"
        ))
        nextShiftContent.addView(dimRow("Starts in ${formatRelative(until)}"))
    }

    private fun renderAllTime(schedule: Schedule?) {
        allTimeContent.removeAllViews()
        if (schedule == null) {
            allTimeContent.addView(dimRow("No schedule cached"))
            return
        }
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val computed = Math.allTime(schedule.blocks, nowSec)
        val totalSec = computed.totalSeconds(nowSec)
        val totalDollars = computed.totalDollarsMs(nowMs, schedule.hourlyRate)

        allTimeContent.addView(infoRow("Hours worked", formatHM(totalSec / 3600.0)))
        allTimeContent.addView(infoRow("Total earned", moneyFmt.format(totalDollars), valueColor = "#3DFF9A"))
        allTimeContent.addView(editableRow(
            "Hourly rate", rateText(schedule.hourlyRate)
        ) { editHourlyRate(schedule.hourlyRate) })
        allTimeContent.addView(editableRow(
            "Calendar event title",
            schedule.workEventTitle.ifBlank { "—" }
        ) { editEventTitle(schedule.workEventTitle) })
        allTimeContent.addView(infoRow("Planned shift", formatHM(schedule.plannedShiftHours)))
        allTimeContent.addView(infoRow("Total blocks", schedule.blocks.size.toString()))
    }

    private fun renderFetched(schedule: Schedule?) {
        if (schedule == null) {
            fetchedLine.text = "Schedule never fetched · awaiting backend"
            return
        }
        val ago = System.currentTimeMillis() / 1000 - schedule.fetchedAt
        fetchedLine.text = "Schedule fetched ${formatRelative(ago)} ago"
    }

    private fun renderPermissions() {
        val whitelisted = isWhitelisted()
        if (whitelisted) {
            batteryStatus.text = "✓  Background activity allowed"
            batteryStatus.setTextColor(Color.parseColor("#3DFF9A"))
            allowBackgroundBtn.visibility = View.GONE
        } else {
            batteryStatus.text = "⚠  Background activity restricted"
            batteryStatus.setTextColor(Color.parseColor("#FF4D5C"))
            allowBackgroundBtn.visibility = View.VISIBLE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                notifStatus.text = "✓  Notifications allowed"
                notifStatus.setTextColor(Color.parseColor("#3DFF9A"))
            } else {
                notifStatus.text = "⚠  Notifications denied"
                notifStatus.setTextColor(Color.parseColor("#FF4D5C"))
            }
        } else {
            notifStatus.visibility = View.GONE
        }
    }

    // ───────────────────────── Permission flow ─────────────────────────

    private fun promptOnFirstLaunchIfNeeded() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PROMPTED, false)) return
        prefs.edit { putBoolean(KEY_PROMPTED, true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        if (!isWhitelisted()) requestBatteryWhitelist()
    }

    private fun isWhitelisted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryWhitelist() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) { /* nothing useful to fall back to */ }
    }

    // ───────────────────────── State / Palette ─────────────────────────

    private enum class State { ON, OFF, SYNC }
    private data class Palette(val state: State, val accent: Int, val accentDim: Int, val label: String)

    private fun palette(schedule: Schedule?): Palette {
        if (schedule == null) {
            return Palette(State.SYNC, Color.parseColor("#FFB22C"), Color.parseColor("#5A3F0A"), "SYNC")
        }
        val now = System.currentTimeMillis() / 1000
        val active = schedule.blocks.any { it.start <= now && now < it.end }
        return if (active) {
            Palette(State.ON, Color.parseColor("#3DFF9A"), Color.parseColor("#1A4A30"), "ON CLOCK")
        } else {
            Palette(State.OFF, Color.parseColor("#FF4D5C"), Color.parseColor("#4A1822"), "OFF DUTY")
        }
    }

    private fun applyHeroBorder(strokeColor: Int) {
        val bg = heroCard.background as? GradientDrawable ?: GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(Color.parseColor("#0B0D10"))
            heroCard.background = this
        }
        bg.setStroke(dp(1) + 1, strokeColor)
    }

    // ───────────────────────── Format helpers ─────────────────────────

    private fun formatHM(hours: Double): String {
        val totalMinutes = (hours * 60).toLong().coerceAtLeast(0)
        return "%d:%02d".format(totalMinutes / 60, totalMinutes % 60)
    }

    private fun rateText(rate: Double): String =
        if (rate == rate.toLong().toDouble()) "$%.0f/HR".format(rate) else "$%.2f/HR".format(rate)

    private fun formatRelative(seconds: Long): String {
        val abs = abs(seconds)
        val d = abs / 86400
        val h = (abs % 86400) / 3600
        val m = (abs % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    // ───────────────────────── UI builders ─────────────────────────

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D10"))
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(32))
        }

        // Title row: WORKTICK + dynamic title on the left, SYNC pill on the right
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleStack.addView(TextView(this).apply {
            text = "WORKTICK"
            textSize = 13f
            letterSpacing = 0.36f
            typeface = monoBold
            setTextColor(Color.parseColor("#6A6E76"))
        })
        titleText = TextView(this).apply {
            text = "—"
            textSize = 22f
            typeface = monoBold
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, 0)
        }
        titleStack.addView(titleText)
        titleRow.addView(titleStack)

        syncButton = TextView(this).apply {
            text = "SYNC NOW"
            textSize = 11f
            letterSpacing = 0.18f
            typeface = monoBold
            setTextColor(Color.parseColor("#3DFF9A"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#0F2A1A"))
                setStroke(dp(1), Color.parseColor("#1A4A30"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { triggerSync(forcePoll = true) }
        }
        titleRow.addView(syncButton)

        container.addView(titleRow)
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
        })

        // Hero card
        container.addView(buildHeroCard())

        // Sections
        container.addView(buildSection("TODAY") { todayContent = it })
        container.addView(buildSection("THIS WEEK") { weekContent = it })
        container.addView(buildSection("NEXT SHIFT") { nextShiftContent = it })
        container.addView(buildSection("ALL TIME") { allTimeContent = it })

        // Schedule meta line
        fetchedLine = TextView(this).apply {
            textSize = 11f
            typeface = monoMedium
            setTextColor(Color.parseColor("#6A6E76"))
            letterSpacing = 0.06f
            setPadding(0, dp(4), 0, dp(28))
        }
        container.addView(fetchedLine)

        // Permissions
        container.addView(sectionHeader("STATUS"))
        batteryStatus = TextView(this).apply {
            textSize = 14f
            typeface = monoBold
            setPadding(0, dp(8), 0, dp(2))
        }
        container.addView(batteryStatus)
        notifStatus = TextView(this).apply {
            textSize = 14f
            typeface = monoBold
            setPadding(0, dp(2), 0, dp(16))
        }
        container.addView(notifStatus)

        allowBackgroundBtn = primaryButton("ALLOW BACKGROUND ACTIVITY") { requestBatteryWhitelist() }
        container.addView(allowBackgroundBtn)
        container.addView(secondaryButton("OPEN APP BATTERY SETTINGS") { openAppDetails() })

        scroll.addView(container)
        return scroll
    }

    private fun buildHeroCard(): View {
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#0B0D10"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(28)
            layoutParams = lp
        }

        // Status row: dot + label · spacer · right-info
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = DotView(this).apply {
            val lp = LinearLayout.LayoutParams(dp(10), dp(10))
            lp.rightMargin = dp(8)
            layoutParams = lp
        }
        statusRow.addView(statusDot)
        statusLabel = TextView(this).apply {
            textSize = 12f
            typeface = monoBold
            letterSpacing = 0.16f
        }
        statusRow.addView(statusLabel)
        statusRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        statusRight = TextView(this).apply {
            textSize = 11f
            typeface = monoBold
            letterSpacing = 0.12f
            setTextColor(Color.parseColor("#CFD3DA"))
        }
        statusRow.addView(statusRight)
        heroCard.addView(statusRow)

        // Hero money
        heroMoney = TextView(this).apply {
            textSize = 48f
            typeface = monoBold
            setTextColor(Color.WHITE)
            letterSpacing = -0.04f
            try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
            includeFontPadding = false
            setShadowLayer(16f, 0f, 0f, Color.argb(160, 255, 255, 255))
            setPadding(0, dp(10), 0, dp(8))
        }
        heroCard.addView(heroMoney)

        // Shift label
        shiftLabel = TextView(this).apply {
            textSize = 12f
            typeface = monoBold
            setTextColor(Color.parseColor("#CFD3DA"))
            letterSpacing = 0.12f
            setPadding(0, 0, 0, dp(8))
        }
        heroCard.addView(shiftLabel)

        // Capped progress bar
        shiftBar = CappedBarView(this).apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
            lp.topMargin = dp(2)
            layoutParams = lp
        }
        heroCard.addView(shiftBar)

        return heroCard
    }

    private fun buildSection(title: String, capture: (LinearLayout) -> Unit): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        wrap.addView(sectionHeader(title))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(20))
        }
        capture(content)
        wrap.addView(content)
        return wrap
    }

    private fun sectionHeader(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        letterSpacing = 0.2f
        typeface = monoBold
        setTextColor(Color.parseColor("#6A6E76"))
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun editableRow(left: String, right: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = left
            textSize = 13f
            typeface = monoMedium
            setTextColor(Color.parseColor("#9A9AA5"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = right
            textSize = 13f
            typeface = monoBold
            setTextColor(Color.parseColor("#3DFF9A"))
            try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
        })
        row.addView(TextView(this).apply {
            text = "  ✎"
            textSize = 12f
            typeface = monoMedium
            setTextColor(Color.parseColor("#6A6E76"))
        })
        return row
    }

    // ───────────────────────── Sync + edit dialogs ─────────────────────────

    private fun triggerSync(forcePoll: Boolean) {
        if (syncButton.text == "SYNCING…") return
        syncButton.text = "SYNCING…"
        syncButton.setTextColor(Color.parseColor("#FFB22C"))
        Thread {
            val error = try {
                if (forcePoll) BackendClient.forcePoll()
                BackendClient.fetchSchedule(this)
                null
            } catch (e: Exception) { e.message ?: e.toString() }
            handler.post {
                if (error == null) {
                    syncButton.text = "SYNCED ✓"
                    syncButton.setTextColor(Color.parseColor("#3DFF9A"))
                    handler.postDelayed({ resetSyncButton() }, 1500)
                    renderEverything()
                } else {
                    syncButton.text = "SYNC FAILED"
                    syncButton.setTextColor(Color.parseColor("#FF4D5C"))
                    Toast.makeText(this, "Sync failed: $error", Toast.LENGTH_LONG).show()
                    handler.postDelayed({ resetSyncButton() }, 2500)
                }
            }
        }.start()
    }

    private fun resetSyncButton() {
        syncButton.text = "SYNC NOW"
        syncButton.setTextColor(Color.parseColor("#3DFF9A"))
    }

    private fun editHourlyRate(current: Double) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (current == current.toLong().toDouble()) "%.0f".format(current) else "%.2f".format(current))
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6A6E76"))
            typeface = monoBold
        }
        showEditDialog(
            title = "Hourly rate",
            subtitle = "USD per hour. Used by widget to compute earnings.",
            input = input,
            onSave = {
                val parsed = input.text.toString().trim().toDoubleOrNull()
                if (parsed == null || parsed < 0) {
                    Toast.makeText(this, "Invalid rate", Toast.LENGTH_SHORT).show()
                    return@showEditDialog false
                }
                pushConfigUpdate(rate = parsed, title = null)
                true
            }
        )
    }

    private fun editEventTitle(current: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(current)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6A6E76"))
            typeface = monoMedium
        }
        showEditDialog(
            title = "Calendar event title",
            subtitle = "Backend re-polls Google Calendar after a change. " +
                       "Old blocks are cleared so only events with the new title count.",
            input = input,
            onSave = {
                val newTitle = input.text.toString().trim()
                if (newTitle.isEmpty()) {
                    Toast.makeText(this, "Title can't be empty", Toast.LENGTH_SHORT).show()
                    return@showEditDialog false
                }
                pushConfigUpdate(rate = null, title = newTitle)
                true
            }
        )
    }

    private fun pushConfigUpdate(rate: Double?, title: String?) {
        syncButton.text = "SYNCING…"
        syncButton.setTextColor(Color.parseColor("#FFB22C"))
        Thread {
            val error = try {
                BackendClient.updateConfig(rate = rate, title = title)
                BackendClient.fetchSchedule(this)
                null
            } catch (e: Exception) { e.message ?: e.toString() }
            handler.post {
                if (error == null) {
                    syncButton.text = "UPDATED ✓"
                    syncButton.setTextColor(Color.parseColor("#3DFF9A"))
                    handler.postDelayed({ resetSyncButton() }, 1500)
                    renderEverything()
                } else {
                    syncButton.text = "FAILED"
                    syncButton.setTextColor(Color.parseColor("#FF4D5C"))
                    Toast.makeText(this, "Update failed: $error", Toast.LENGTH_LONG).show()
                    handler.postDelayed({ resetSyncButton() }, 2500)
                }
            }
        }.start()
    }

    private fun showEditDialog(
        title: String,
        subtitle: String,
        input: EditText,
        onSave: () -> Boolean
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        container.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            typeface = monoMedium
            setTextColor(Color.parseColor("#9A9AA5"))
            setPadding(0, 0, 0, dp(12))
        })
        container.addView(input)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Save", null)  // overridden below to keep dialog open on validation fail
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (onSave()) dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun infoRow(left: String, right: String, valueColor: String = "#FFFFFF"): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = left
            textSize = 13f
            typeface = monoMedium
            setTextColor(Color.parseColor("#9A9AA5"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = right
            textSize = 13f
            typeface = monoBold
            setTextColor(Color.parseColor(valueColor))
            try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
        })
        return row
    }

    private fun blockRow(b: Schedule.Block, dur: Long, rate: Double, state: String): View {
        val zone = ZoneId.systemDefault()
        val startStr = Instant.ofEpochSecond(b.start).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        val endStr = Instant.ofEpochSecond(b.end).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        val (badgeColor, badgeBg) = when (state) {
            "NOW" -> Pair("#3DFF9A", "#1A4A30")
            "DONE" -> Pair("#9A9AA5", "#1A1D22")
            else -> Pair("#FFB22C", "#5A3F0A")
        }
        row.addView(TextView(this).apply {
            text = state
            textSize = 9f
            typeface = monoBold
            letterSpacing = 0.18f
            setTextColor(Color.parseColor(badgeColor))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor(badgeBg))
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(10)
            layoutParams = lp
        })
        row.addView(TextView(this).apply {
            text = "$startStr → $endStr"
            textSize = 13f
            typeface = monoMedium
            setTextColor(Color.parseColor("#CFD3DA"))
            try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = formatHM(dur / 3600.0)
            textSize = 13f
            typeface = monoBold
            setTextColor(Color.parseColor("#CFD3DA"))
            try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(12)
            layoutParams = lp
        })
        row.addView(TextView(this).apply {
            text = moneyFmt.format(dur.toDouble() * rate / 3600.0)
            textSize = 13f
            typeface = monoBold
            setTextColor(Color.WHITE)
            try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
        })
        return row
    }

    private fun totalRow(totalSec: Long, rate: Double): View {
        return infoRow(
            "Total", "${formatHM(totalSec / 3600.0)}   ${moneyFmt.format(totalSec.toDouble() * rate / 3600.0)}",
            valueColor = "#3DFF9A"
        )
    }

    private fun weekTotalRow(totalSec: Long, totalDollars: Double): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = "WEEK"
            textSize = 11f
            letterSpacing = 0.18f
            typeface = monoBold
            setTextColor(Color.parseColor("#9A9AA5"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "${formatHM(totalSec / 3600.0)}   ${moneyFmt.format(totalDollars)}"
            textSize = 13f
            typeface = monoBold
            setTextColor(Color.parseColor("#3DFF9A"))
            try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
        })
        return row
    }

    private fun weekDayRow(date: LocalDate, seconds: Long, rate: Double, isToday: Boolean): View {
        val pct = (seconds / 3600.0 / 8.0).toFloat().coerceIn(0f, 1f)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US).uppercase()
            textSize = 11f
            typeface = monoBold
            letterSpacing = 0.16f
            setTextColor(if (isToday) Color.parseColor("#3DFF9A") else Color.parseColor("#9A9AA5"))
            val lp = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
        })
        // Mini bar
        val miniBar = MiniBarView(this).apply {
            this.pct = pct
            this.accent = if (isToday) Color.parseColor("#3DFF9A") else Color.parseColor("#CFD3DA")
            this.dim = Color.parseColor("#1A1D22")
            val lp = LinearLayout.LayoutParams(0, dp(6), 1f)
            lp.rightMargin = dp(10)
            lp.leftMargin = dp(4)
            layoutParams = lp
        }
        row.addView(miniBar)
        row.addView(TextView(this).apply {
            text = if (seconds == 0L) "—" else formatHM(seconds / 3600.0)
            textSize = 12f
            typeface = monoMedium
            setTextColor(Color.parseColor("#CFD3DA"))
            try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
            val lp = LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
        })
        row.addView(TextView(this).apply {
            text = if (seconds == 0L) "" else moneyFmt.format(seconds.toDouble() * rate / 3600.0)
            textSize = 12f
            typeface = monoBold
            setTextColor(Color.WHITE)
            gravity = Gravity.END
            try { fontFeatureSettings = "tnum" } catch (_: Throwable) {}
            val lp = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
        })
        return row
    }

    private fun dimRow(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = monoMedium
        setTextColor(Color.parseColor("#6A6E76"))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Color.parseColor("#0B0D10"))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#3DFF9A"))
        }
        typeface = monoBold
        textSize = 13f
        letterSpacing = 0.14f
        setPadding(dp(20), dp(14), dp(20), dp(14))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, dp(8))
        layoutParams = lp
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Color.parseColor("#CFD3DA"))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#1A1D22"))
            setStroke(dp(1), Color.parseColor("#2A2E34"))
        }
        typeface = monoBold
        textSize = 13f
        letterSpacing = 0.14f
        setPadding(dp(20), dp(14), dp(20), dp(14))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, dp(8))
        layoutParams = lp
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "worktick"
        private const val KEY_PROMPTED = "first_launch_prompts_done"
        private const val REQ_NOTIF = 100
    }
}

/** Status indicator dot that matches the widget's style — solid fill + soft glow when ON. */
class DotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private var fillColor = Color.parseColor("#3DFF9A")
    private var withGlow = false

    fun setColor(color: Int, glow: Boolean) {
        fillColor = color
        withGlow = glow
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f) - 1f
        if (withGlow) {
            glow.color = fillColor; glow.alpha = 110
            canvas.drawCircle(cx, cy, r, glow)
        }
        paint.color = fillColor
        canvas.drawCircle(cx, cy, r, paint)
    }
}

/** Capped horizontal progress bar — same look as the widget's bottom rule. */
class CappedBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    var pct: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var accent: Int = Color.parseColor("#3DFF9A")
        set(value) { field = value; invalidate() }
    var accentDim: Int = Color.parseColor("#1A4A30")
        set(value) { field = value; invalidate() }

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f; color = Color.parseColor("#1A1D22") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f }

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        val capH = height * 0.7f
        val left = 1f
        val right = width - 1f
        capPaint.color = accentDim
        canvas.drawLine(left, y - capH / 2, left, y + capH / 2, capPaint)
        canvas.drawLine(right, y - capH / 2, right, y + capH / 2, capPaint)
        canvas.drawLine(left + 1f, y, right - 1f, y, trackPaint)
        fillPaint.color = accent
        canvas.drawLine(left + 1f, y, left + 1f + (right - left - 2f) * pct, y, fillPaint)
    }
}

/** Skinnier capped bar for week-day rows. */
class MiniBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    var pct: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var accent: Int = Color.parseColor("#3DFF9A")
    var dim: Int = Color.parseColor("#1A1D22")

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f }

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        trackPaint.color = dim
        canvas.drawLine(0f, y, width.toFloat(), y, trackPaint)
        fillPaint.color = accent
        canvas.drawLine(0f, y, width * pct, y, fillPaint)
    }
}
