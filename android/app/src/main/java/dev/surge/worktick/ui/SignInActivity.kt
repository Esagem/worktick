package dev.surge.worktick.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import dev.surge.worktick.MainActivity
import dev.surge.worktick.MoneyTickerWidgetProvider
import dev.surge.worktick.ScheduleStore
import dev.surge.worktick.WidgetTickerService
import dev.surge.worktick.auth.GoogleAuthManager
import dev.surge.worktick.calendar.SchedulePoller
import kotlinx.coroutines.launch

/**
 * One-time Google sign-in flow. Bounces to MainActivity once tokens are
 * persisted and the first poll succeeds (or the user taps Continue past a
 * poll error).
 */
class SignInActivity : ComponentActivity() {

    private lateinit var auth: GoogleAuthManager
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var progress: ProgressBar

    private val authorizeLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            setStatus("Sign-in cancelled.")
            setBusy(false)
            return@registerForActivityResult
        }
        val intent: Intent? = result.data
        if (intent == null) {
            setStatus("Sign-in returned no data.")
            setBusy(false)
            return@registerForActivityResult
        }
        try {
            val authResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(intent)
            handleResult(authResult)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse authorization result", e)
            setStatus("Could not parse sign-in result: ${e.message}")
            setBusy(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = GoogleAuthManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0D10"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "WORKTICK"
            textSize = 28f
            setTextColor(Color.parseColor("#3DFF9A"))
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Connect your Google Calendar to begin"
            textSize = 14f
            setTextColor(Color.parseColor("#CFD3DA"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(40))
        })
        button = Button(this).apply {
            text = "Sign in with Google"
            setOnClickListener { startSignIn() }
        }
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(0, dp(16), 0, 0)
        }
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#8A8F97"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(button)
        root.addView(progress)
        root.addView(status)
        setContentView(root)
    }

    private fun startSignIn() {
        setBusy(true)
        setStatus("Opening Google account picker…")
        lifecycleScope.launch {
            try {
                val initial = auth.beginAuthorization(this@SignInActivity)
                if (initial.hasResolution()) {
                    val pi = initial.pendingIntent
                    if (pi == null) {
                        setStatus("No PendingIntent on resolution.")
                        setBusy(false)
                        return@launch
                    }
                    authorizeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    handleResult(initial)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Authorization start failed", e)
                setStatus("Failed: ${e.message}")
                setBusy(false)
            }
        }
    }

    private fun handleResult(result: AuthorizationResult) {
        setStatus("Exchanging authorization code…")
        lifecycleScope.launch {
            val ok = auth.persistFromAuthorizationResult(result)
            if (!ok) {
                setStatus("No refresh token returned. In Google Account → Security → Third-party apps, remove WorkTick and try again.")
                setBusy(false)
                return@launch
            }
            setStatus("Fetching schedule…")
            when (val pollResult = SchedulePoller(this@SignInActivity).pollOnce()) {
                is SchedulePoller.Result.Ok -> {
                    ScheduleStore.write(this@SignInActivity, pollResult.schedule)
                    MoneyTickerWidgetProvider.requestUpdate(this@SignInActivity)
                    val now = System.currentTimeMillis() / 1000
                    val active = pollResult.schedule.blocks.any { it.start <= now && now < it.end }
                    if (active) WidgetTickerService.start(this@SignInActivity)
                    finishToMain()
                }
                is SchedulePoller.Result.Error -> {
                    setStatus("Poll failed: ${pollResult.message}\nTap Continue to proceed anyway.")
                    button.text = "Continue"
                    button.setOnClickListener { finishToMain() }
                    setBusy(false)
                }
                SchedulePoller.Result.NotAuthenticated -> {
                    setStatus("Authentication lost. Please try again.")
                    setBusy(false)
                }
            }
        }
    }

    private fun finishToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    private fun setStatus(text: String) { status.text = text }
    private fun setBusy(busy: Boolean) {
        button.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "SignInActivity"
    }
}
