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
}
