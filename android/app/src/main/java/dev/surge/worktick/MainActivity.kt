package dev.surge.worktick

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit

class MainActivity : Activity() {

    private lateinit var batteryStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var allowBackgroundBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            if (active) WidgetTickerService.start(this)
            else WidgetTickerService.stop(this)
        }
        MoneyTickerWidgetProvider.requestUpdate(this)

        promptOnFirstLaunchIfNeeded()
        refreshStatus()
    }

    /**
     * Two prompts the OS gates behind a system dialog and we should ask for once,
     * then never auto-prompt again — the user can re-trigger from the buttons:
     *  1. POST_NOTIFICATIONS (API 33+) — required for the foreground service to
     *     post its silent ongoing notification.
     *  2. REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — the standard Android battery
     *     allowlist, which on Samsung maps to Battery → Unrestricted.
     */
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

    private fun refreshStatus() {
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
                notifStatus.text = "⚠  Notifications denied (foreground ticker won't run)"
                notifStatus.setTextColor(Color.parseColor("#FF4D5C"))
            }
        } else {
            notifStatus.visibility = View.GONE
        }
    }

    private fun isWhitelisted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryWhitelist() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
            startActivity(intent)
        } catch (_: Exception) { /* nothing useful to fall back to */ }
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D10"))
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(56), dp(28), dp(40))
        }

        container.addView(TextView(this).apply {
            text = "WorkTick"
            textSize = 36f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        container.addView(TextView(this).apply {
            text = "Long-press your home screen → Widgets → WorkTick"
            textSize = 14f
            setTextColor(Color.parseColor("#9A9AA5"))
            setPadding(0, dp(6), 0, dp(36))
        })

        container.addView(sectionHeader("STATUS"))

        batteryStatus = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(2))
        }
        container.addView(batteryStatus)

        notifStatus = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(16))
        }
        container.addView(notifStatus)

        allowBackgroundBtn = primaryButton("Allow background activity") { requestBatteryWhitelist() }
        container.addView(allowBackgroundBtn)

        container.addView(sectionHeader("SAMSUNG TUNING"))

        container.addView(TextView(this).apply {
            text = "For consistent sub-second cent ticking on Samsung devices, also " +
                    "add WorkTick to Settings → Battery → Background usage limits → " +
                    "Never sleeping apps. (No public API exists to set this " +
                    "programmatically — the button below deep-links to the page.)"
            textSize = 13f
            setLineSpacing(0f, 1.3f)
            setTextColor(Color.parseColor("#9A9AA5"))
            setPadding(0, dp(8), 0, dp(16))
        })

        container.addView(secondaryButton("Open app battery settings") { openAppDetails() })

        scroll.addView(container)
        return scroll
    }

    private fun sectionHeader(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        letterSpacing = 0.18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#6A6E76"))
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Color.parseColor("#0B0D10"))
        setBackgroundColor(Color.parseColor("#3DFF9A"))
        typeface = Typeface.DEFAULT_BOLD
        textSize = 14f
        setPadding(dp(20), dp(12), dp(20), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(4), 0, dp(8))
        layoutParams = lp
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(Color.parseColor("#CFD3DA"))
        setBackgroundColor(Color.parseColor("#1A1D22"))
        typeface = Typeface.DEFAULT_BOLD
        textSize = 14f
        setPadding(dp(20), dp(12), dp(20), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(4), 0, dp(8))
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "worktick"
        private const val KEY_PROMPTED = "first_launch_prompts_done"
        private const val REQ_NOTIF = 100
    }
}
