package com.mardous.booming.online.spotify

import android.content.Context
import com.mardous.booming.online.data.spotify.SpotifyPlaylist
import com.mardous.booming.online.data.spotify.SpotifyTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk cache of loaded Spotify playlists (and Liked Songs) so they can be
 * reopened offline and searched without network access.
 */
object SpotifyPlaylistCache {

    const val LIKED_KEY = "liked"

    @Serializable
    data class Cached(
        val playlist: SpotifyPlaylist? = null,
        val tracks: List<SpotifyTrack> = emptyList()
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "playlist_cache").apply { mkdirs() }

    private fun cacheFile(context: Context, key: String): File =
        File(cacheDir(context), "${key.replace(Regex("[^\\w-]"), "_")}.json")

    fun save(context: Context, key: String, playlist: SpotifyPlaylist?, tracks: List<SpotifyTrack>) {
        if (tracks.isEmpty()) return
        runCatching {
            cacheFile(context, key).writeText(json.encodeToString(Cached(playlist, tracks)))
        }
    }

    fun read(context: Context, key: String): Cached? = runCatching {
        val file = cacheFile(context, key)
        if (file.exists()) {
            json.decodeFromString<Cached>(file.readText())
        } else null
    }.getOrNull()

    fun loadTracks(context: Context, key: String): List<SpotifyTrack> =
        read(context, key)?.tracks.orEmpty()
}
