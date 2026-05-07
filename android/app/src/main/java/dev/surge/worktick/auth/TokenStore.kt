package dev.surge.worktick.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme
import androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme
import androidx.security.crypto.MasterKeys

/**
 * Stores OAuth refresh + access tokens in EncryptedSharedPreferences (AES256-GCM
 * at rest, key bound to the device's KeyStore). The refresh token is the
 * long-lived credential that grants Calendar access; the access token is
 * short-lived (~1 hour) and refreshed on demand.
 */
class TokenStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val keyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        prefs = EncryptedSharedPreferences.create(
            PREFS_NAME,
            keyAlias,
            context,
            PrefKeyEncryptionScheme.AES256_SIV,
            PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit { putString(KEY_REFRESH, value) }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = prefs.edit { putString(KEY_ACCESS, value) }

    /** Unix seconds when the access token expires. 0 = unknown/expired. */
    var accessTokenExpiresAt: Long
        get() = prefs.getLong(KEY_ACCESS_EXPIRES, 0)
        set(value) = prefs.edit { putLong(KEY_ACCESS_EXPIRES, value) }

    fun isAuthenticated(): Boolean = !refreshToken.isNullOrBlank()

    fun clear() {
        prefs.edit {
            remove(KEY_REFRESH)
            remove(KEY_ACCESS)
            remove(KEY_ACCESS_EXPIRES)
        }
    }

    companion object {
        private const val PREFS_NAME = "worktick_secure_tokens"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_ACCESS_EXPIRES = "access_expires_at"
    }
}
