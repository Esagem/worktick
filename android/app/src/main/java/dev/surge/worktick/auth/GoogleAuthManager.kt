package dev.surge.worktick.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dev.surge.worktick.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OAuth flow using Google's Identity Authorization API.
 *
 *   1) SignInActivity calls beginAuthorization() to get a Task<AuthorizationResult>.
 *   2) If hasResolution() → SignInActivity launches the PendingIntent via
 *      ActivityResultLauncher; user picks Google account + grants Calendar scope.
 *   3) SignInActivity passes the resulting Intent through
 *      Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent
 *      and calls persistFromAuthorizationResult().
 *   4) We exchange the serverAuthCode for refresh + access tokens at the OAuth
 *      token endpoint and persist via TokenStore.
 *
 * Subsequent token refresh happens silently in getAccessToken() via the
 * refresh_token grant.
 */
class GoogleAuthManager(private val context: Context) {

    private val tokens = TokenStore(context)
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    fun isAuthenticated(): Boolean = tokens.isAuthenticated()

    fun buildAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_CALENDAR_READONLY)))
            .requestOfflineAccess(BuildConfig.GOOGLE_OAUTH_CLIENT_ID, true)
            .build()
    }

    suspend fun beginAuthorization(activity: Activity): AuthorizationResult =
        suspendCancellableCoroutine { cont ->
            val client = Identity.getAuthorizationClient(activity)
            val task: Task<AuthorizationResult> = client.authorize(buildAuthorizationRequest())
            task.addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    /** Returns true if a refresh token was successfully obtained and stored. */
    suspend fun persistFromAuthorizationResult(result: AuthorizationResult): Boolean {
        val authCode = result.serverAuthCode
        if (authCode.isNullOrBlank()) {
            Log.w(TAG, "AuthorizationResult had no serverAuthCode")
            return false
        }
        return exchangeAuthCode(authCode)
    }

    private suspend fun exchangeAuthCode(serverAuthCode: String): Boolean = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("code", serverAuthCode)
            .add("client_id", BuildConfig.GOOGLE_OAUTH_CLIENT_ID)
            .add("client_secret", BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET)
            .add("redirect_uri", "")
            .add("grant_type", "authorization_code")
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Token exchange HTTP ${resp.code}: $text")
                    return@withContext false
                }
                val json = JSONObject(text)
                val refresh = json.optString("refresh_token", "")
                val access = json.optString("access_token", "")
                val expiresIn = json.optInt("expires_in", 3600)
                if (refresh.isBlank() || access.isBlank()) {
                    Log.w(TAG, "Exchange response missing fields: $json")
                    return@withContext false
                }
                tokens.refreshToken = refresh
                tokens.accessToken = access
                tokens.accessTokenExpiresAt = System.currentTimeMillis() / 1000 + expiresIn - 60
                Log.i(TAG, "Tokens persisted (access expires in ${expiresIn}s)")
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Token exchange failed", e)
            false
        }
    }

    /**
     * Returns a valid access token, refreshing if within 30s of expiry.
     * Throws NotAuthenticatedException if there's no refresh token or it's been revoked.
     */
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val refresh = tokens.refreshToken
            ?: throw NotAuthenticatedException("Not signed in")
        val nowSec = System.currentTimeMillis() / 1000
        val current = tokens.accessToken
        if (current != null && tokens.accessTokenExpiresAt > nowSec + 30) {
            return@withContext current
        }
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.GOOGLE_OAUTH_CLIENT_ID)
            .add("client_secret", BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                Log.w(TAG, "Refresh HTTP ${resp.code}: $text")
                if (resp.code == 400 || resp.code == 401) {
                    tokens.clear()
                    throw NotAuthenticatedException("Refresh token rejected — please sign in again")
                }
                throw IOException("Refresh failed: HTTP ${resp.code}")
            }
            val json = JSONObject(text)
            val access = json.optString("access_token", "")
            val expiresIn = json.optInt("expires_in", 3600)
            if (access.isBlank()) throw IOException("Refresh response missing access_token")
            tokens.accessToken = access
            tokens.accessTokenExpiresAt = nowSec + expiresIn - 60
            access
        }
    }

    /** Revoke the refresh token at Google and clear local storage. Best-effort. */
    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        val refresh = tokens.refreshToken
        tokens.clear()
        if (refresh.isNullOrBlank()) return@withContext
        try {
            val req = Request.Builder()
                .url("https://oauth2.googleapis.com/revoke?token=$refresh")
                .post(FormBody.Builder().build())
                .build()
            http.newCall(req).execute().close()
        } catch (e: IOException) {
            Log.w(TAG, "Revoke failed (local tokens already cleared)", e)
        }
    }

    class NotAuthenticatedException(msg: String) : Exception(msg)

    companion object {
        private const val TAG = "GoogleAuthManager"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val SCOPE_CALENDAR_READONLY = "https://www.googleapis.com/auth/calendar.readonly"
    }
}
