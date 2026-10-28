package com.mardous.booming.online.data.spotify

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * SharedPreferences helper for all Spotify-related persistence.
 * Uses the same "AppSettings" prefs file as the rest of SpatialFlow for consistency.
 */
object SpotifyPrefs {

    private const val PREFS_NAME = "AppSettings"

    private const val KEY_SP_DC = "spotify_sp_dc"
    private const val KEY_SP_KEY = "spotify_sp_key"
    private const val KEY_ACCESS_TOKEN = "spotify_access_token"
    private const val KEY_TOKEN_EXPIRES_AT = "spotify_access_token_expires_at"
    private const val KEY_ACCOUNT_NAME = "spotify_account_name"
    private const val KEY_ACCOUNT_AVATAR_URL = "spotify_account_avatar_url"
    private const val KEY_PLAYLISTS_CACHE = "spotify_library_playlists_cache"
    private const val KEY_SHOW_SPOTIFY = "show_spotify_playlists"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── sp_dc cookie ──────────────────────────────────────────────────────────

    fun getSpDc(context: Context): String = prefs(context).getString(KEY_SP_DC, "").orEmpty()
    fun saveSpDc(context: Context, value: String) = prefs(context).edit { putString(KEY_SP_DC, value) }

    // ── sp_key cookie ─────────────────────────────────────────────────────────

    fun getSpKey(context: Context): String = prefs(context).getString(KEY_SP_KEY, "").orEmpty()
    fun saveSpKey(context: Context, value: String) = prefs(context).edit { putString(KEY_SP_KEY, value) }

    // ── Access token ──────────────────────────────────────────────────────────

    fun getAccessToken(context: Context): String =
        prefs(context).getString(KEY_ACCESS_TOKEN, "").orEmpty()

    fun saveAccessToken(context: Context, token: String) =
        prefs(context).edit { putString(KEY_ACCESS_TOKEN, token) }

    fun getTokenExpiresAt(context: Context): Long =
        prefs(context).getLong(KEY_TOKEN_EXPIRES_AT, 0L)

    fun saveTokenExpiresAt(context: Context, expiresAt: Long) =
        prefs(context).edit { putLong(KEY_TOKEN_EXPIRES_AT, expiresAt) }

    fun isTokenValid(context: Context): Boolean {
        val token = getAccessToken(context)
        val expiresAt = getTokenExpiresAt(context)
        return token.isNotBlank() && System.currentTimeMillis() < expiresAt - 60_000L
    }

    fun isTokenExpiringSoon(context: Context, bufferMs: Long = 300_000L): Boolean {
        val token = getAccessToken(context)
        val expiresAt = getTokenExpiresAt(context)
        return token.isBlank() || System.currentTimeMillis() >= expiresAt - bufferMs
    }

    // ── Account metadata ──────────────────────────────────────────────────────

    fun getAccountName(context: Context): String =
        prefs(context).getString(KEY_ACCOUNT_NAME, "").orEmpty()

    fun saveAccountName(context: Context, name: String) =
        prefs(context).edit { putString(KEY_ACCOUNT_NAME, name) }

    fun getAccountAvatarUrl(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT_AVATAR_URL, null)
            ?.takeIf(String::isNotBlank)

    fun saveAccountAvatarUrl(context: Context, url: String?) =
        prefs(context).edit { putString(KEY_ACCOUNT_AVATAR_URL, url) }

    // ── Playlists cache (raw JSON string) ────────────────────────────────────

    fun getPlaylistsCache(context: Context): String? =
        prefs(context).getString(KEY_PLAYLISTS_CACHE, null)
            ?.takeIf(String::isNotBlank)

    fun savePlaylistsCache(context: Context, json: String) =
        prefs(context).edit { putString(KEY_PLAYLISTS_CACHE, json) }

    fun clearPlaylistsCache(context: Context) =
        prefs(context).edit { remove(KEY_PLAYLISTS_CACHE) }

    // ── Visibility toggle ─────────────────────────────────────────────────────

    fun isShowSpotify(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_SPOTIFY, true)

    fun setShowSpotify(context: Context, show: Boolean) =
        prefs(context).edit { putBoolean(KEY_SHOW_SPOTIFY, show) }

    // ── Is connected check ────────────────────────────────────────────────────

    fun isConnected(context: Context): Boolean = getSpDc(context).isNotBlank()

    // ── Full logout ───────────────────────────────────────────────────────────

    fun clearAll(context: Context) = prefs(context).edit {
        remove(KEY_SP_DC)
        remove(KEY_SP_KEY)
        remove(KEY_ACCESS_TOKEN)
        remove(KEY_TOKEN_EXPIRES_AT)
        remove(KEY_ACCOUNT_NAME)
        remove(KEY_ACCOUNT_AVATAR_URL)
        remove(KEY_PLAYLISTS_CACHE)
    }

    // ── Artist image URL persistence ─────────────────────────────────────
    //
    // Resolved Spotify artist-image URLs, keyed by the normalized artist
    // name. Persisted so artist pictures survive restarts and work offline
    // (the Coil disk cache then serves the image itself). The map is
    // insertion-ordered and trimmed to [MAX_ARTIST_IMAGE_URLS] entries.

    private const val KEY_ARTIST_IMAGE_URLS = "spotify_artist_image_urls"
    private const val MAX_ARTIST_IMAGE_URLS = 500

    fun getArtistImageUrl(context: Context, nameKey: String): String? {
        val map = readArtistImageUrls(context)
        return map[nameKey]?.takeIf { it.isNotBlank() }
    }

    fun setArtistImageUrl(context: Context, nameKey: String, url: String?) {
        val map = readArtistImageUrls(context).toMutableMap()
        if (url.isNullOrBlank()) {
            map.remove(nameKey)
        } else {
            map[nameKey] = url
        }
        while (map.size > MAX_ARTIST_IMAGE_URLS) {
            map.remove(map.keys.first())
        }
        val json = buildJsonObject {
            for ((name, imageUrl) in map) put(name, imageUrl)
        }
        prefs(context).edit { putString(KEY_ARTIST_IMAGE_URLS, json.toString()) }
    }

    private fun readArtistImageUrls(context: Context): Map<String, String> {
        val stored = prefs(context).getString(KEY_ARTIST_IMAGE_URLS, null) ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(stored).jsonObject.entries.associate { (name, value) ->
                name to value.jsonPrimitive.content
            }
        }.getOrDefault(emptyMap())
    }

    // ── Album cover URL persistence ──────────────────────────────────────
    //
    // Resolved Spotify album-cover URLs, keyed by the normalized
    // "artist|album" pair. Persisted so online covers survive restarts
    // and work offline (the Coil disk cache then serves the image
    // itself). The map is insertion-ordered and trimmed to
    // [MAX_ALBUM_COVER_URLS] entries.

    private const val KEY_ALBUM_COVER_URLS = "spotify_album_cover_urls"
    private const val MAX_ALBUM_COVER_URLS = 1000

    fun getAlbumCoverUrl(context: Context, key: String): String? {
        val map = readAlbumCoverUrls(context)
        return map[key]?.takeIf { it.isNotBlank() }
    }

    fun setAlbumCoverUrl(context: Context, key: String, url: String?) {
        val map = readAlbumCoverUrls(context).toMutableMap()
        if (url.isNullOrBlank()) {
            map.remove(key)
        } else {
            map[key] = url
        }
        while (map.size > MAX_ALBUM_COVER_URLS) {
            map.remove(map.keys.first())
        }
        val json = buildJsonObject {
            for ((coverKey, imageUrl) in map) put(coverKey, imageUrl)
        }
        prefs(context).edit { putString(KEY_ALBUM_COVER_URLS, json.toString()) }
    }

    private fun readAlbumCoverUrls(context: Context): Map<String, String> {
        val stored = prefs(context).getString(KEY_ALBUM_COVER_URLS, null) ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(stored).jsonObject.entries.associate { (key, value) ->
                key to value.jsonPrimitive.content
            }
        }.getOrDefault(emptyMap())
    }
}
