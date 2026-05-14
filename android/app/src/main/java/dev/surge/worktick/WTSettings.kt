package dev.surge.worktick

import android.content.Context
import androidx.core.content.edit

/**
 * User-configurable settings backed by SharedPreferences. Read by the Settings
 * UI, the poller, and the widget provider.
 */
object WTSettings {
    private const val PREFS = "worktick_settings"
    private const val KEY_RATE = "hourly_rate"
    private const val KEY_TITLE = "event_title"
    private const val KEY_LAST_POLL = "last_poll_at"
    private const val KEY_LAST_ERROR = "last_poll_error"
    private const val KEY_PORTAL_URL = "portal_url"
    private const val KEY_NOTIFY_LEAD_MIN = "notify_lead_min"

    private const val DEFAULT_TITLE = "McCrary Summer Work"
    private const val DEFAULT_RATE = 30.0
    private const val DEFAULT_LEAD_MIN = 5

    fun hourlyRate(context: Context): Double =
        prefs(context).getFloat(KEY_RATE, DEFAULT_RATE.toFloat()).toDouble()

    fun setHourlyRate(context: Context, value: Double) {
        prefs(context).edit { putFloat(KEY_RATE, value.toFloat()) }
    }

    fun eventTitle(context: Context): String =
        prefs(context).getString(KEY_TITLE, DEFAULT_TITLE) ?: DEFAULT_TITLE

    fun setEventTitle(context: Context, value: String) {
        prefs(context).edit { putString(KEY_TITLE, value.trim()) }
    }

    fun lastPollAt(context: Context): Long = prefs(context).getLong(KEY_LAST_POLL, 0L)
    fun setLastPollAt(context: Context, value: Long) {
        prefs(context).edit { putLong(KEY_LAST_POLL, value) }
    }

    fun lastPollError(context: Context): String? = prefs(context).getString(KEY_LAST_ERROR, null)
    fun setLastPollError(context: Context, value: String?) {
        prefs(context).edit {
            if (value == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, value)
        }
    }

    /** Portal URL opened when the user taps a clock-in/out reminder notification. */
    fun portalUrl(context: Context): String =
        prefs(context).getString(KEY_PORTAL_URL, "")?.trim().orEmpty()

    fun setPortalUrl(context: Context, value: String) {
        prefs(context).edit { putString(KEY_PORTAL_URL, value.trim()) }
    }

    /** Minutes before a block boundary to fire the clock-in/out reminder. 0 = at the boundary. */
    fun notifyLeadMinutes(context: Context): Int =
        prefs(context).getInt(KEY_NOTIFY_LEAD_MIN, DEFAULT_LEAD_MIN).coerceAtLeast(0)

    fun setNotifyLeadMinutes(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_NOTIFY_LEAD_MIN, value.coerceAtLeast(0)) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
