package com.mardous.booming.ui.screen.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyHomeItem
import com.mardous.booming.online.data.spotify.SpotifyTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SpotifyArtistViewModel(application: Application) : AndroidViewModel(application) {

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _albums = MutableStateFlow<List<SpotifyHomeItem.Album>>(emptyList())
    val albums = _albums.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun load(artistId: String) {
        if (artistId.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _tracks.value = SpotifyClient.artistTopTracks(artistId)
                // Album shelf loads on the same gate as the tracks.
                _albums.value = SpotifyClient.artistAlbums(artistId).map { album ->
                    com.mardous.booming.online.data.spotify.SpotifyHomeItem.Album(
                        uri = "spotify:album:${album.id}",
                        id = album.id,
                        name = album.name,
                        artists = listOf(
                            com.mardous.booming.online.data.spotify.SpotifySimpleArtist(
                                name = album.artistName
                            )
                        ),
                        imageUrl = album.imageUrl
                    )
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }
}
