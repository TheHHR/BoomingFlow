package com.mardous.booming.ui.screen.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyPlaylist
import com.mardous.booming.online.data.spotify.SpotifyTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SpotifyPlaylistViewModel(application: Application) : AndroidViewModel(application) {

    private val _playlist = MutableStateFlow<SpotifyPlaylist?>(null)
    val playlist = _playlist.asStateFlow()

    private val _isLiked = MutableStateFlow(false)
    val isLiked = _isLiked.asStateFlow()

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private var loaded = false

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Serializable
    private data class PlaylistCache(
        val playlist: SpotifyPlaylist? = null,
        val tracks: List<SpotifyTrack> = emptyList()
    )

    private fun cacheDir(): File =
        File(getApplication<Application>().filesDir, "playlist_cache").apply { mkdirs() }

    private fun cacheFile(key: String): File =
        File(cacheDir(), "${key.replace(Regex("[^\\w-]"), "_")}.json")

    private fun saveCache(key: String, playlist: SpotifyPlaylist?, tracks: List<SpotifyTrack>) {
        if (tracks.isEmpty()) return
        runCatching {
            cacheFile(key).writeText(json.encodeToString(PlaylistCache(playlist, tracks)))
        }
    }

    private fun readCache(key: String): PlaylistCache? = runCatching {
        val file = cacheFile(key)
        if (file.exists()) {
            json.decodeFromString<PlaylistCache>(file.readText())
        } else null
    }.getOrNull()

    fun load(playlistId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            if (playlistId == null) loadLikedSongs() else loadPlaylist(playlistId)
        }
    }

    private suspend fun loadPlaylist(playlistId: String) {
        var playlist: SpotifyPlaylist? = null
        SpotifyClient.playlist(playlistId)
            .onSuccess { playlist = it; _playlist.value = it }
            .onFailure {
                val cache = readCache(playlistId)
                if (cache != null && cache.tracks.isNotEmpty()) {
                    cache.playlist?.let { _playlist.value = it }
                    _tracks.value = cache.tracks
                    _loading.value = false
                    return
                }
                _loading.value = false
                return
            }

        var offset = 0
        var hasMore = true
        val allTracks = mutableListOf<SpotifyTrack>()
        while (hasMore) {
            SpotifyClient.playlistTracks(playlistId, limit = 100, offset = offset)
                .onSuccess { page ->
                    val pageTracks = page.items.mapNotNull { item ->
                        item.track?.takeUnless { track -> track.isLocal }
                    }
                    allTracks += pageTracks
                    _tracks.value = allTracks.toList()
                    offset += page.items.size
                    hasMore = page.items.size == 100 && offset < page.total
                }
                .onFailure { hasMore = false }
        }
        if (allTracks.isNotEmpty()) {
            saveCache(playlistId, playlist, allTracks)
        } else {
            readCache(playlistId)?.let { cache ->
                if (cache.tracks.isNotEmpty()) {
                    cache.playlist?.let { _playlist.value = it }
                    _tracks.value = cache.tracks
                }
            }
        }
        _loading.value = false
    }

    private suspend fun loadLikedSongs() {
        _isLiked.value = true
        var offset = 0
        var hasMore = true
        var anyPageSucceeded = false
        val allTracks = mutableListOf<SpotifyTrack>()
        while (hasMore) {
            SpotifyClient.likedSongs(limit = 50, offset = offset)
                .onSuccess { page ->
                    anyPageSucceeded = true
                    allTracks += page.items.mapNotNull { it.track }
                    _tracks.value = allTracks.toList()
                    offset += page.items.size
                    hasMore = page.items.size == 50 && offset < page.total
                }
                .onFailure { hasMore = false }
        }
        if (allTracks.isNotEmpty()) {
            saveCache(LIKED_CACHE_KEY, null, allTracks)
        } else if (!anyPageSucceeded) {
            readCache(LIKED_CACHE_KEY)?.let { cache ->
                if (cache.tracks.isNotEmpty()) {
                    _tracks.value = cache.tracks
                }
            }
        }
        _loading.value = false
    }

    companion object {
        private const val LIKED_CACHE_KEY = "liked"
    }
}
