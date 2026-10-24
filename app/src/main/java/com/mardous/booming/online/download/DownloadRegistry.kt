package com.mardous.booming.online.download

import android.content.Context
import android.content.SharedPreferences

/**
 * Keeps track of the files this app downloaded for online (YouTube Music) tracks.
 * Maps a videoId to the published MediaStore/file Uri so playback can prefer
 * the local copy and downloads can be skipped when already done.
 */
object DownloadRegistry {

    private const val PREFS_NAME = "booming_download_registry"
    private const val KEY_PREFIX = "uri_"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(context: Context, videoId: String, uri: String, displayName: String) {
        prefs(context).edit()
            .putString(KEY_PREFIX + videoId, uri)
            .putString("name_" + videoId, displayName)
            .apply()
    }

    fun get(context: Context, videoId: String): String? =
        prefs(context).getString(KEY_PREFIX + videoId, null)

    fun displayName(context: Context, videoId: String): String? =
        prefs(context).getString("name_" + videoId, null)

    fun all(context: Context): Map<String, String> =
        prefs(context).all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && it.value is String }
            .associate { it.key.removePrefix(KEY_PREFIX) to it.value as String }

    fun remove(context: Context, videoId: String) {
        prefs(context).edit()
            .remove(KEY_PREFIX + videoId)
            .remove("name_" + videoId)
            .apply()
    }

    fun removeByUri(context: Context, uri: String) {
        val editor = prefs(context).edit()
        prefs(context).all.forEach { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value == uri) {
                editor.remove(key).remove("name_" + key.removePrefix(KEY_PREFIX))
            }
        }
        editor.apply()
    }

    fun hideDownloaded(context: Context): Boolean =
        prefs(context).getBoolean("hide_downloaded", true)

    fun setHideDownloaded(context: Context?, value: Boolean) {
        context?.let { prefs(it).edit().putBoolean("hide_downloaded", value).apply() }
    }

    fun autoDownload(context: Context, playlistKey: String): Boolean =
        prefs(context).getBoolean("auto_download_$playlistKey", false)

    fun setAutoDownload(context: Context, playlistKey: String, value: Boolean) {
        prefs(context).edit().putBoolean("auto_download_$playlistKey", value).apply()
    }

    // ── Context-free helpers (app-wide registry initialized at startup) ───────

    fun get(videoId: String): String? = appContext?.let { get(it, videoId) }

    fun spotifyTrackUri(trackId: String): String? {
        if (trackId.isBlank()) return null
        return appContext?.let { get(it, "sp:$trackId") }
    }
}
