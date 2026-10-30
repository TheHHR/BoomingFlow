package com.mardous.booming.ui.screen.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyHomeFeed
import com.mardous.booming.online.data.spotify.SpotifyHomeItem
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Spotify shelf for the fused Artists and Albums library pages.
 *
 * Shows the user's Spotify artists/albums (from the cached Discover
 * home feed - artists and albums sections) below the local grid.
 * Hidden when logged out, offline, or when the feed has no matching
 * section: empty dataSet = no section.
 */
class SpotifyLibrarySectionsViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _artists = MutableStateFlow<List<SpotifyHomeItem.Artist>>(emptyList())
    val artists = _artists.asStateFlow()

    private val _albums = MutableStateFlow<List<SpotifyHomeItem.Album>>(emptyList())
    val albums = _albums.asStateFlow()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch(Dispatchers.IO) {
            // Gated exactly like the Playlists shelf: a stored sp_dc
            // session counts as connected for display.
            if (!SpotifyPrefs.isConnected(context)) return@launch
            if (!SpotifyClient.isAuthenticated()) return@launch
            SpotifyClient.home()
                .onSuccess { feed ->
                    _artists.value = feed.sections
                        .flatMap { it.items }
                        .filterIsInstance<SpotifyHomeItem.Artist>()
                        .distinctBy { it.id }
                    _albums.value = feed.sections
                        .flatMap { it.items }
                        .filterIsInstance<SpotifyHomeItem.Album>()
                        .distinctBy { it.id }
                    if (_artists.value.isNotEmpty() || _albums.value.isNotEmpty()) {
                        runCatching { cacheFile().writeText(json.encodeToString(feed)) }
                    }
                }
                .onFailure {
                    // Offline: restore the cached feed silently so the
                    // section can still show; an empty cache hides it.
                    runCatching {
                        val file = cacheFile()
                        if (file.exists()) {
                            val feed = json.decodeFromString<SpotifyHomeFeed>(file.readText())
                            _artists.value = feed.sections
                                .flatMap { it.items }
                                .filterIsInstance<SpotifyHomeItem.Artist>()
                                .distinctBy { it.id }
                            _albums.value = feed.sections
                                .flatMap { it.items }
                                .filterIsInstance<SpotifyHomeItem.Album>()
                                .distinctBy { it.id }
                        }
                    }
                }
        }
    }

    private fun cacheFile(): File {
        val dir = File(context.filesDir, "discover_cache").apply { mkdirs() }
        return File(dir, "home.json")
    }
}
