package com.mardous.booming.ui.screen.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SpotifyAlbumViewModel(application: Application) : AndroidViewModel(application) {

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    fun load(albumId: String) {
        if (albumId.isBlank() || _tracks.value.isNotEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            try {
                _tracks.value = SpotifyClient.albumTracks(albumId)
            } finally {
                _loading.value = false
            }
        }
    }
}
