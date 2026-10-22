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

class PlaylistsSectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _likedTotal = MutableStateFlow<Int?>(null)
    val likedTotal = _likedTotal.asStateFlow()

    private val _spotifyPlaylists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val spotifyPlaylists = _spotifyPlaylists.asStateFlow()

    private var loaded = false

    fun load() = viewModelScope.launch {
        if (loaded || !SpotifyPrefs.isConnected(context)) return@launch
        loaded = true
        coroutineScope {
            launch {
                SpotifyClient.likedSongs(limit = 50, offset = 0)
                    .onSuccess { page -> _likedTotal.value = page.total }
                    .onFailure { loaded = false }
            }
            launch {
                val allPlaylists = mutableListOf<SpotifyPlaylist>()
                var offset = 0
                var hasMore = true
                while (hasMore) {
                    SpotifyClient.myPlaylists(limit = 50, offset = offset)
                        .onSuccess { page ->
                            allPlaylists += page.items
                            _spotifyPlaylists.value = allPlaylists.toList()
                            offset += page.items.size
                            hasMore = page.items.size == 50 && offset < page.total
                        }
                        .onFailure { hasMore = false }
                }
            }
        }
    }
}
