package com.mardous.booming.ui.screen.library.playlists

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.data.local.room.PlaylistEntity
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
     * Persists the per-playlist sort choice. `null` key means Custom
     * order (the manual drag order stored in the Room rows); switching
     * to Custom also clears the descending flag.
     */
    fun setPlaylistSort(sortKey: String?) {
        updateSortEntity { it.copy(sortMode = sortKey, sortDescending = false) }
    }

    /** Toggles the descending flag, reading the fresh entity from the DAO. */
    fun toggleSortDescending() {
        updateSortEntity { it.copy(sortDescending = !it.sortDescending) }
    }

    private fun updateSortEntity(transform: (PlaylistEntity) -> PlaylistEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val entity = playlistRepository.playlistWithSongs(playlistId).playlistEntity
                if (entity.playListId != -1L) {
                    playlistRepository.updatePlaylist(transform(entity))
                }
            }
        }
    }
}
