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

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        _uiState.value = DiscoverUiState.Loading
        if (!SpotifyPrefs.isConnected(context)) {
            _uiState.value = DiscoverUiState.NotConnected
            return@launch
        }
        SpotifyClient.home()
            .onSuccess { feed ->
                _uiState.value = if (feed.sections.isEmpty()) {
                    DiscoverUiState.Empty
                } else {
                    DiscoverUiState.Success(feed)
                }
            }
            .onFailure { error ->
                _uiState.value = DiscoverUiState.Error(error.message)
            }
    }
}
