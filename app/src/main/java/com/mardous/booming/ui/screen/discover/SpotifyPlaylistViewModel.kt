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

class SpotifyPlaylistViewModel(
    application: Application,
    private val playlistId: String
) : AndroidViewModel(application) {

    private val _playlist = MutableStateFlow<SpotifyPlaylist?>(null)
    val playlist = _playlist.asStateFlow()

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    init {
        load()
    }

    private fun load() = viewModelScope.launch {
        SpotifyClient.playlist(playlistId)
            .onSuccess { playlist -> _playlist.value = playlist }
            .onFailure {
                _loading.value = false
                return@launch
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
}
