package com.mardous.booming.ui.screen.library.playlists

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyPlaylist
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class PlaylistsSectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _likedTotal = MutableStateFlow<Int?>(null)
    val likedTotal = _likedTotal.asStateFlow()

    private val _spotifyPlaylists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val spotifyPlaylists = _spotifyPlaylists.asStateFlow()

    private var loaded = false

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class SectionsCache(
        val likedTotal: Int? = null,
        val playlists: List<SpotifyPlaylist> = emptyList()
    )

    private fun cacheFile(): File {
        val dir = File(context.filesDir, "playlist_cache").apply { mkdirs() }
        return File(dir, "sections.json")
    }

    private fun saveCache() {
        runCatching {
            cacheFile().writeText(
                json.encodeToString(SectionsCache(_likedTotal.value, _spotifyPlaylists.value))
            )
        }
    }

    private fun restoreCache(): Boolean = runCatching {
        val file = cacheFile()
        if (!file.exists()) return false
        val cache = json.decodeFromString<SectionsCache>(file.readText())
        if (cache.likedTotal == null && cache.playlists.isEmpty()) return false
        if (_likedTotal.value == null) _likedTotal.value = cache.likedTotal
        if (_spotifyPlaylists.value.isEmpty()) _spotifyPlaylists.value = cache.playlists
        true
    }.getOrDefault(false)

    fun load() = viewModelScope.launch {
        if (loaded || !SpotifyPrefs.isConnected(context)) return@launch
        loaded = true
        coroutineScope {
            val liked = async {
                SpotifyClient.likedSongs(limit = 50, offset = 0)
                    .onSuccess { page -> _likedTotal.value = page.total }
                    .isFailure
            }
            val playlists = async {
                val allPlaylists = mutableListOf<SpotifyPlaylist>()
                var offset = 0
                var hasMore = true
                var failed = false
                while (hasMore) {
                    SpotifyClient.myPlaylists(limit = 50, offset = offset)
                        .onSuccess { page ->
                            allPlaylists += page.items
                            _spotifyPlaylists.value = allPlaylists.toList()
                            offset += page.items.size
                            hasMore = page.items.size == 50 && offset < page.total
                        }
                        .onFailure { hasMore = false; failed = failed || allPlaylists.isEmpty() }
                }
                failed
            }
            val likedFailed = liked.await()
            val playlistsFailed = playlists.await()
            if (likedFailed || playlistsFailed) {
                restoreCache()
            } else {
                saveCache()
            }
        }
    }
}
