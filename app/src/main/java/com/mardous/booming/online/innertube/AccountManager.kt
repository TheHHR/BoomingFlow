package com.mardous.booming.online.data.innertube

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Facilitates application authentication state holding user-facing identity tokens 
 * and enabling account sync state tracking.
 */
object AccountManager {

    private const val PREFS_NAME = "AppSettings"
    private const val KEY_COOKIE_DATA = "yt_cookies"
    private const val KEY_SYNC_ENABLED = "sync_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isLoggedIn(context: Context): Boolean {
        return !getAuthCookie(context).isNullOrEmpty()
    }

    fun getAccountName(context: Context): String {
        return if (isLoggedIn(context)) "Connected Account" else "Guest User"
    }

    fun getAuthCookie(context: Context): String? {
        return getPrefs(context).getString(KEY_COOKIE_DATA, null)
    }

    fun isSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SYNC_ENABLED, true)
    }

    /**
     * Parses and normalizes various user-provided cookie input formats (header, multiline, raw SAPISID).
     */
    fun parseAndNormalizeCookie(rawInput: String): String {
        var cleaned = rawInput.trim()
        if (cleaned.startsWith("Cookie:", ignoreCase = true)) {
            cleaned = cleaned.substringAfter(":").trim()
        }
        cleaned = cleaned.removeSurrounding("\"").removeSurrounding("'").trim()

        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val cookiePairs = mutableListOf<String>()

        for (line in lines) {
            val parts = line.split(';')
            for (part in parts) {
                val trimmed = part.trim()
                if (trimmed.isNotEmpty() && trimmed.contains("=")) {
                    cookiePairs.add(trimmed)
                }
            }
        }

        var result = cookiePairs.joinToString("; ")
        if (result.isBlank()) {
            if (cleaned.isNotBlank() && !cleaned.contains("=")) {
                result = "SAPISID=$cleaned; __Secure-3PAPISID=$cleaned"
            } else {
                result = cleaned
            }
        }
        return result
    }

    /**
     * Checks if cookie string contains essential security credentials for YouTube Music InnerTube.
     */
    fun hasEssentialCookies(cookie: String): Boolean {
        val upper = cookie.uppercase()
        return upper.contains("SAPISID") || upper.contains("__SECURE-3PAPISID") || upper.contains("SID=")
    }

    /**
     * Tests whether the provided cookie grants authenticated access to YouTube Music.
     * If valid, persists credentials and returns the fetched UserProfile.
     */
    suspend fun testAndSaveCookie(context: Context, rawCookie: String): Result<UserProfile> {
        val normalized = parseAndNormalizeCookie(rawCookie)
        if (normalized.isBlank()) {
            return Result.failure(IllegalArgumentException("Cookie input cannot be empty."))
        }
        if (!hasEssentialCookies(normalized)) {
            return Result.failure(IllegalArgumentException("Cookie string must contain 'SAPISID' or '__Secure-3PAPISID'."))
        }

        val previousCookie = InnerTubeClient.cookie
        return try {
            InnerTubeClient.cookie = normalized
            InnerTubeClient.visitorData = null

            val profileResult = YouTubeMusic.accountProfile()
            if (profileResult.isSuccess) {
                val profile = profileResult.getOrThrow()
                saveLoginState(context, normalized)
                Result.success(profile)
            } else {
                InnerTubeClient.cookie = previousCookie
                val err = profileResult.exceptionOrNull() ?: Exception("Failed to validate YouTube session")
                Result.failure(err)
            }
        } catch (e: Exception) {
            InnerTubeClient.cookie = previousCookie
            Result.failure(e)
        }
    }

    fun saveLoginState(context: Context, cookie: String) {
        // Update persistent store
        getPrefs(context).edit {
            putString(KEY_COOKIE_DATA, cookie)
        }
        // Update immediate memory runtime in InnerTube facade
        InnerTubeClient.cookie = cookie
        InnerTubeClient.visitorData = null
    }

    fun setSyncEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_SYNC_ENABLED, enabled) }
    }

    fun logout(context: Context) {
        // Remove cookie from persistent storage
        getPrefs(context).edit { remove(KEY_COOKIE_DATA) }
        // Wipe InnerTube runtime
        InnerTubeClient.cookie = null
        InnerTubeClient.visitorData = null
    }
}
