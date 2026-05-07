package dev.surge.worktick.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.surge.worktick.MoneyTickerWidgetProvider
import dev.surge.worktick.ScheduleStore
import dev.surge.worktick.WTSettings
import dev.surge.worktick.WidgetTickerService
import dev.surge.worktick.auth.GoogleAuthManager
import dev.surge.worktick.calendar.SchedulePoller
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Account + diagnostics screen. Rate / event-title editing happens inline in
 * MainActivity, so this screen is just: force a refresh, sign out, and inspect
 * cached state. Reachable from the Settings button in MainActivity.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var diagnostics: TextView
    private lateinit var refreshBtn: Button
    private lateinit var signOutBtn: Button
    private lateinit var auth: GoogleAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = GoogleAuthManager(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D10"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(root)

        root.addView(header("ACCOUNT"))
        refreshBtn = button("Force refresh now") { onForceRefresh() }
        root.addView(refreshBtn)
        signOutBtn = button("Sign out") { onSignOut() }
        root.addView(signOutBtn)

        root.addView(divider())

        root.addView(header("DIAGNOSTICS"))
        diagnostics = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#CFD3DA"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#1A1D22"))
        }
        root.addView(diagnostics)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshDiagnostics()
    }

    private fun onForceRefresh() {
        refreshBtn.isEnabled = false
        refreshBtn.text = "Refreshing…"
        lifecycleScope.launch {
            when (val result = SchedulePoller(this@SettingsActivity).pollOnce()) {
                is SchedulePoller.Result.Ok -> {
                    ScheduleStore.write(this@SettingsActivity, result.schedule)
                    MoneyTickerWidgetProvider.requestUpdate(this@SettingsActivity)
                    val now = System.currentTimeMillis() / 1000
                    val active = result.schedule.blocks.any { it.start <= now && now < it.end }
                    if (active) WidgetTickerService.start(this@SettingsActivity)
                    else WidgetTickerService.stop(this@SettingsActivity)
                    toast("Refreshed: ${result.schedule.blocks.size} blocks")
                }
                is SchedulePoller.Result.Error -> toast("Refresh failed: ${result.message}")
                SchedulePoller.Result.NotAuthenticated -> {
                    toast("Sign-in expired — please sign in again")
                    startActivity(Intent(this@SettingsActivity, SignInActivity::class.java))
                    finish()
                }
            }
            refreshBtn.isEnabled = true
            refreshBtn.text = "Force refresh now"
            refreshDiagnostics()
        }
    }

    private fun onSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage("This clears your stored Google credentials. You'll need to sign in again to fetch new schedules.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ -> doSignOut() }
            .show()
    }

    private fun doSignOut() {
        signOutBtn.isEnabled = false
        signOutBtn.text = "Signing out…"
        lifecycleScope.launch {
            try {
                auth.signOut()
            } catch (e: Exception) {
                Log.w(TAG, "signOut error (continuing)", e)
            }
            WidgetTickerService.stop(this@SettingsActivity)
            startActivity(Intent(this@SettingsActivity, SignInActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            })
            finish()
        }
    }

    private fun refreshDiagnostics() {
        val sb = StringBuilder()
        sb.append("Authenticated: ${auth.isAuthenticated()}\n")
        val lastPoll = WTSettings.lastPollAt(this)
        if (lastPoll > 0) {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            sb.append("Last poll: ${fmt.format(Date(lastPoll * 1000L))}\n")
        } else {
            sb.append("Last poll: never\n")
        }
        WTSettings.lastPollError(this)?.let { sb.append("Last error: $it\n") }
        val cached = ScheduleStore.read(this)
        if (cached != null) {
            val now = System.currentTimeMillis() / 1000
            val active = cached.blocks.count { it.start <= now && now < it.end }
            val upcoming = cached.blocks.count { it.start > now }
            val past = cached.blocks.count { it.end <= now }
            sb.append("\nCached schedule:\n")
            sb.append("  blocks: ${cached.blocks.size}  (active=$active, past=$past, future=$upcoming)\n")
            sb.append("  rate: \$${cached.hourlyRate}/hr\n")
            val fetched = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(cached.fetchedAt * 1000L))
            sb.append("  fetched: $fetched\n")
        } else {
            sb.append("\nNo cached schedule.\n")
        }
        sb.append("\nEvent title filter: \"${WTSettings.eventTitle(this)}\"\n")
        sb.append("Hourly rate: \$${WTSettings.hourlyRate(this)}/hr\n")
        diagnostics.text = sb.toString()
    }

    // ---------- UI helpers ----------

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#3DFF9A"))
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.18f
        setPadding(0, dp(8), 0, dp(12))
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

    private fun divider(): android.view.View = android.view.View(this).apply {
        setBackgroundColor(Color.parseColor("#1A1D22"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply { topMargin = dp(24); bottomMargin = dp(8) }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
