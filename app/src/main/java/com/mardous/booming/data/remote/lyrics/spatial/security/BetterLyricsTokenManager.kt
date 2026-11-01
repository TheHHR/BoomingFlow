package com.mardous.booming.data.remote.lyrics.spatial.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ported from SpatialFlow. Manages the Cloudflare Turnstile challenge
 * resolution and JWT authentication for the BetterLyrics unified API
 * (Portato QQ QRC + Musixmatch richsync):
 *
 *  - An off-screen headless WebView solves the Turnstile challenge at
 *    `https://lyrics.api.dacubeking.com/challenge`.
 *  - The turnstile token is exchanged at `/verify-turnstile` for a JWT.
 *  - The JWT is cached in SharedPreferences with a 5-minute renewal
 *    buffer and reused until expiry.
 */
object BetterLyricsTokenManager {
    private const val TAG = "BetterLyricsToken"

    private const val PREFS_NAME = "BoomingUnifiedToken"
    private const val KEY_JWT = "unified_jwt_token"
    private const val KEY_EXPIRES_AT = "unified_jwt_expires_at"

    private const val TURNSTILE_CHALLENGE_URL = "https://lyrics.api.dacubeking.com/challenge"
    private const val VERIFY_TURNSTILE_URL = "https://lyrics.api.dacubeking.com/verify-turnstile"

    private const val RENEWAL_BUFFER_SECONDS = 300L // 5 minutes buffer
    private const val SOLVER_TIMEOUT_MS = 30_000L

    private val isSolving = AtomicBoolean(false)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jwt = prefs.getString(KEY_JWT, null)
        if (jwt.isNullOrEmpty()) return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return if (expiresAt > System.currentTimeMillis() / 1000) jwt else null
    }

    fun saveToken(context: Context, token: String, expiresAtSec: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_JWT, token)
            .putLong(KEY_EXPIRES_AT, expiresAtSec)
            .apply()
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_JWT).remove(KEY_EXPIRES_AT).apply()
    }

    /**
     * Returns a valid JWT, solving the Turnstile challenge if missing or
     * expired.
     */
    suspend fun getOrRefreshToken(context: Context, forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            getToken(context)?.let { return it }
        } else {
            clearToken(context)
        }
        return solveTurnstile(context)
    }

    /**
     * Solves the Cloudflare Turnstile challenge using a headless
     * off-screen Android WebView.
     */
    private suspend fun solveTurnstile(context: Context): String? {
        if (!isSolving.compareAndSet(false, true)) {
            // Wait up to 10 seconds for a concurrent solve to finish.
            for (i in 0..20) {
                delay(500)
                getToken(context)?.let { return it }
                if (!isSolving.get()) break
            }
            return getToken(context)
        }

        val turnstileDeferred = CompletableDeferred<String?>()

        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    val webView = WebView(context.applicationContext)
                    val settings = webView.settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                    class TurnstileBridge {
                        @JavascriptInterface
                        fun onToken(token: String) {
                            if (!turnstileDeferred.isCompleted) {
                                turnstileDeferred.complete(token)
                            }
                            destroyWebView(webView)
                        }

                        @JavascriptInterface
                        fun onError(error: String) {
                            Log.w(TAG, "Turnstile error: $error")
                            if (!turnstileDeferred.isCompleted) {
                                turnstileDeferred.complete(null)
                            }
                            destroyWebView(webView)
                        }
                    }

                    webView.addJavascriptInterface(TurnstileBridge(), "AndroidTurnstile")

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val js = """
                                (function() {
                                    window.addEventListener('message', function(e) {
                                        if (e.data && e.data.type === 'turnstile-token' && e.data.token) {
                                            AndroidTurnstile.onToken(e.data.token);
                                        } else if (e.data && e.data.type === 'turnstile-error') {
                                            AndroidTurnstile.onError(e.data.error || 'unknown');
                                        }
                                    });
                                    if (typeof onTurnstileSuccess === 'function') {
                                        var origSuccess = onTurnstileSuccess;
                                        window.onTurnstileSuccess = function(t) {
                                            AndroidTurnstile.onToken(t);
                                            origSuccess(t);
                                        };
                                    }
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                        }
                    }

                    webView.loadUrl(TURNSTILE_CHALLENGE_URL)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize WebView for Turnstile", e)
                    turnstileDeferred.complete(null)
                }
            }

            val turnstileToken = withTimeoutOrNull(SOLVER_TIMEOUT_MS) {
                turnstileDeferred.await()
            }

            if (turnstileToken.isNullOrEmpty()) {
                Log.w(TAG, "Turnstile challenge timed out or failed to yield token")
                return null
            }

            val jwt = exchangeTurnstileTokenForJwt(turnstileToken)
            if (jwt != null) {
                val exp = extractJwtExpiry(jwt)
                    ?: (System.currentTimeMillis() / 1000 + 86400)
                saveToken(context, jwt, exp)
                return jwt
            }
            return null
        } finally {
            isSolving.set(false)
        }
    }

    private fun destroyWebView(webView: WebView) {
        Handler(Looper.getMainLooper()).post {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (_: Exception) {}
        }
    }

    private fun exchangeTurnstileTokenForJwt(turnstileToken: String): String? {
        return try {
            val jsonPayload = JsonObject().apply {
                addProperty("token", turnstileToken)
            }
            val body = jsonPayload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(VERIFY_TURNSTILE_URL)
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "verify-turnstile failed with HTTP ${response.code}")
                    return null
                }
                val respStr = response.body?.string() ?: return null
                val json = JsonParser.parseString(respStr).asJsonObject
                json.get("jwt")?.asString
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exchanging Turnstile token", e)
            null
        }
    }

    private fun extractJwtExpiry(jwt: String): Long? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payloadB64 = parts[1]
                .replace('-', '+')
                .replace('_', '/')
            val padded = payloadB64.padEnd(
                payloadB64.length + ((4 - (payloadB64.length % 4)) % 4),
                '='
            )
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            val json = JsonParser.parseString(decoded).asJsonObject
            json.get("exp")?.asLong
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode JWT payload: ${e.message}")
            null
        }
    }
}
