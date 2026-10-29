package com.mardous.booming.ui.screen.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyHomeFeed
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

sealed interface DiscoverUiState {
    data object Loading : DiscoverUiState
    data object NotConnected : DiscoverUiState
    data object Empty : DiscoverUiState
    data class Success(val feed: SpotifyHomeFeed) : DiscoverUiState
    data class Error(val message: String?) : DiscoverUiState
}

class DiscoverViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _uiState = MutableStateFlow<DiscoverUiState>(DiscoverUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private var loaded = false

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init {
        restoreCache()
        load()
    }

    /**
     * Loads the home feed once per ViewModel lifetime; pass [force] for
     * explicit refreshes (menu refresh, retry, reconnect). A restored
     * cache keeps the shelves visible and is silently revalidated.
     */
    fun load(force: Boolean = false) = viewModelScope.launch {
        if (loaded && !force) return@launch
        loaded = true
        if (_uiState.value !is DiscoverUiState.Success) {
            _uiState.value = DiscoverUiState.Loading
        }
        if (!SpotifyPrefs.isConnected(context)) {
            _uiState.value = DiscoverUiState.NotConnected
            return@launch
        }
        if (!SpotifyClient.isAuthenticated()) {
            // User token not installed yet (session restore in progress).
            // Never fall back to an anonymous token here — it would render a
            // generic feed as if it were the account's own home page.
            return@launch
        }
        SpotifyClient.home()
            .onSuccess { feed ->
                if (feed.sections.isEmpty()) {
                    _uiState.value = DiscoverUiState.Empty
                } else {
                    _uiState.value = DiscoverUiState.Success(feed)
                    saveCache(feed)
                }
            }
        .onFailure { error ->
            // A refresh failure must not blank out the cached shelves.
            if (_uiState.value !is DiscoverUiState.Success) {
                _uiState.value = DiscoverUiState.Error(error.message)
            }
        }
    }

    private fun cacheFile(): File {
        val dir = File(context.filesDir, "discover_cache").apply { mkdirs() }
        return File(dir, "home.json")
    }

    private fun saveCache(feed: SpotifyHomeFeed) {
        runCatching { cacheFile().writeText(json.encodeToString(feed)) }
    }

    private fun restoreCache(): Boolean = runCatching {
        val file = cacheFile()
        if (!file.exists()) return false
        val feed = json.decodeFromString<SpotifyHomeFeed>(file.readText())
        if (feed.sections.isEmpty()) return false
        _uiState.value = DiscoverUiState.Success(feed)
        true
    }.getOrDefault(false)

    fun onNotConnected() {
        _uiState.value = DiscoverUiState.NotConnected
    }
}
