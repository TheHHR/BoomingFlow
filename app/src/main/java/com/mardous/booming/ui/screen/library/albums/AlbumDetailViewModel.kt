package com.mardous.booming.ui.screen.library.albums

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import com.mardous.booming.online.data.spotify.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AlbumDetailViewModel(
    application: Application,
    private val repository: Repository,
    private val albumId: Long
) : AndroidViewModel(application) {

    private val _albumDetail = MutableLiveData<Album>()

    fun getAlbumDetail(): LiveData<Album> = _albumDetail

    fun getAlbum() = getAlbumDetail().value ?: Album.empty

    fun loadAlbumDetail() = viewModelScope.launch(Dispatchers.IO) {
        val album = repository.albumById(albumId)
        _albumDetail.postValue(album)
        loadSpotifyMatch(album)
    }

    /**
     * Spotify counterpart of this local album, matched by name so the
     * full tracklist can render below the local songs. Empty when the
     * account is disconnected or nothing matches.
     */
    private val _spotifyTracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val spotifyTracks = _spotifyTracks.asStateFlow()

    private var spotifyLoaded = false

    private suspend fun loadSpotifyMatch(album: Album) {
        if (spotifyLoaded) return
        spotifyLoaded = true
        val context = getApplication<Application>().applicationContext
        if (!SpotifyPrefs.isConnected(context)) return
        if (!SpotifyClient.isAuthenticated()) return
        val artist = album.albumArtistName ?: album.artistName
        val candidates = SpotifyClient.searchAlbums("${album.name} $artist", limit = 5)
        val match = candidates.firstOrNull {
            it.name.equals(album.name, ignoreCase = true)
        } ?: candidates.firstOrNull()
        match ?: return
        _spotifyTracks.value = SpotifyClient.albumTracks(match.id)
    }

    fun getSimilarAlbums(album: Album): LiveData<List<Album>> = liveData(Dispatchers.IO) {
        repository.similarAlbums(album).let {
            if (it.isNotEmpty()) emit(it)
        }
    }

    fun getAlbumWiki(album: Album, lang: String?) = liveData(Dispatchers.IO) {
        if (NetworkFeature.Lastfm.Biographies.isAvailable) {
            emit(repository.albumInfo(album.albumArtistName ?: album.artistName, album.name, lang))
        }
    }
}