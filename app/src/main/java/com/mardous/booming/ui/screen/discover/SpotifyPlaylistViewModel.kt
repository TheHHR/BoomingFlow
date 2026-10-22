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
        SpotifyClient.playlist(playlistId)
            .onSuccess { playlist -> _playlist.value = playlist }
            .onFailure {
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
        _loading.value = false
    }

    private suspend fun loadLikedSongs() {
        _isLiked.value = true
        var offset = 0
        var hasMore = true
        val allTracks = mutableListOf<SpotifyTrack>()
        while (hasMore) {
            SpotifyClient.likedSongs(limit = 50, offset = offset)
                .onSuccess { page ->
                    allTracks += page.items.mapNotNull { it.track }
                    _tracks.value = allTracks.toList()
                    offset += page.items.size
                    hasMore = page.items.size == 50 && offset < page.total
                }
                .onFailure { hasMore = false }
        }
        _loading.value = false
    }
}
