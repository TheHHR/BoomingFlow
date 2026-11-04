package com.mardous.booming.ui.screen.library.playlists

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.data.local.room.SongEntity
import com.mardous.booming.data.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    private val playlistRepository: PlaylistRepository,
    private var playlistId: Long
) : ViewModel() {

    init {
        // Synced playlists refresh their rules on every open; the Room
        // LiveData below then delivers the updated song list.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { playlistRepository.syncPlaylist(playlistId) }
        }
    }

    fun getSongs(): LiveData<List<SongEntity>> =
        playlistRepository.playlistSongsObservable(playlistId)

    fun playlistExists(): LiveData<Boolean> =
        playlistRepository.checkPlaylistExists(playlistId)

    fun getPlaylist() = playlistRepository.playlistWithSongsObservable(playlistId)

    /**
     * Persists the per-playlist sort choice. `null` key means the manual
     * (drag) order stored in the Room rows.
     */
    fun setPlaylistSort(sortKey: String?, descending: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val entity = playlistRepository.playlistWithSongs(playlistId).playlistEntity
                if (entity.playListId != -1L) {
                    playlistRepository.updatePlaylist(
                        entity.copy(sortMode = sortKey, sortDescending = descending)
                    )
                }
            }
        }
    }
}
