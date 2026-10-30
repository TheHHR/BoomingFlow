package com.mardous.booming.ui.screen.discover

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyPlaylist
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.spotify.SpotifyPlaylistCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    fun load(playlistId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            if (playlistId == null) loadLikedSongs() else loadPlaylist(playlistId)
        }
    }

    private suspend fun loadPlaylist(playlistId: String) {
        // Show the cached playlist instantly (cover + tracks) while the
        // network refresh runs - same pattern as the liked-songs path.
        SpotifyPlaylistCache.read(getApplication(), playlistId)?.let { cache ->
            if (cache.tracks.isNotEmpty()) {
                cache.playlist?.let { _playlist.value = it }
                _tracks.value = cache.tracks
                _loading.value = false
            }
        }

        var playlist: SpotifyPlaylist? = null
        SpotifyClient.playlist(playlistId)
            .onSuccess { playlist = it; _playlist.value = it }
            .onFailure {
                if (_tracks.value.isEmpty()) {
                    val cache = SpotifyPlaylistCache.read(getApplication(), playlistId)
                    if (cache != null && cache.tracks.isNotEmpty()) {
                        cache.playlist?.let { _playlist.value = it }
                        _tracks.value = cache.tracks
                    }
                }
                _loading.value = false
                return
            }

        var offset = 0
        var hasMore = true
        var anyPageFailed = false
        val allTracks = mutableListOf<SpotifyTrack>()
        while (hasMore) {
            SpotifyClient.playlistTracks(playlistId, limit = 100, offset = offset)
                .onSuccess { page ->
                    val pageTracks = page.items.mapNotNull { item ->
                        item.track?.takeUnless { track -> track.isLocal }
                    }
                    for (track in pageTracks) {
                        if (allTracks.none { it.id == track.id }) {
                            allTracks.add(track)
                        }
                    }
                    _tracks.value = allTracks.toList()
                    offset += page.items.size
                    hasMore = page.items.isNotEmpty() && offset < page.total
                }
                .onFailure {
                    hasMore = false
                    anyPageFailed = true
                }
        }
        if (allTracks.isNotEmpty() && !anyPageFailed) {
            SpotifyPlaylistCache.save(getApplication(), playlistId, playlist, allTracks)
        } else if (allTracks.isEmpty()) {
            SpotifyPlaylistCache.read(getApplication(), playlistId)?.let { cache ->
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
        val cacheKey = SpotifyPlaylistCache.LIKED_KEY

        // Show the cached list instantly while the network refresh runs.
        val cached = SpotifyPlaylistCache.read(getApplication(), cacheKey)?.tracks.orEmpty()
        if (cached.isNotEmpty()) {
            _tracks.value = cached
            _loading.value = false
        }

        var offset = 0
        var hasMore = true
        var anyPageFailed = false
        val allTracks = mutableListOf<SpotifyTrack>()
        while (hasMore) {
            SpotifyClient.likedSongs(limit = 50, offset = offset)
                .onSuccess { page ->
                    val pageTracks = page.items.mapNotNull { it.track }
                    for (track in pageTracks) {
                        if (allTracks.none { it.id == track.id }) {
                            allTracks.add(track)
                        }
                    }
                    offset += page.items.size
                    // Keep paging while the library total hasn't been reached
                    // and the API keeps returning items. A short page is NOT
                    // the end of the list — only total/empty-page is.
                    hasMore = page.items.isNotEmpty() && offset < page.total
                }
                .onFailure { error ->
                    Log.w("SpotifyPlaylistVM", "Liked songs page $offset failed", error)
                    hasMore = false
                    anyPageFailed = true
                }
        }
        if (allTracks.isNotEmpty()) {
            // Single final emission — no intermediate partial states.
            _tracks.value = allTracks
            if (!anyPageFailed) {
                SpotifyPlaylistCache.save(getApplication(), cacheKey, null, allTracks)
            }
        } else if (cached.isEmpty()) {
            SpotifyPlaylistCache.read(getApplication(), cacheKey)?.let { cache ->
                if (cache.tracks.isNotEmpty()) {
                    _tracks.value = cache.tracks
                }
            }
        }
        _loading.value = false
    }
}
