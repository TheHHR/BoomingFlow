/*
 * Copyright (c) 2024 Christians MartA-nez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.ui.screen.library.playlists

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.content.edit
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.R
import com.mardous.booming.core.model.GridViewType
import com.mardous.booming.core.sort.PlaylistSortMode
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.navigation.playlistDetailArgs
import com.mardous.booming.extensions.navigation.spotifyPlaylistArgs
import com.mardous.booming.online.data.spotify.SpotifyHomeItem
import com.mardous.booming.online.data.spotify.SpotifyPlaylist
import com.mardous.booming.online.data.spotify.thumbnailUrl
import com.mardous.booming.online.viewmodel.SpotifyAuthState
import com.mardous.booming.ui.IPlaylistCallback
import com.mardous.booming.ui.adapters.HorizontalListAdapter
import com.mardous.booming.ui.adapters.PlaylistAdapter
import com.mardous.booming.ui.adapters.SectionHeaderAdapter
import com.mardous.booming.ui.component.base.AbsRecyclerViewCustomGridSizeFragment
import com.mardous.booming.ui.component.menu.onPlaylistMenu
import com.mardous.booming.ui.component.menu.onPlaylistsMenu
import com.mardous.booming.ui.dialogs.playlists.CreatePlaylistDialog
import com.mardous.booming.ui.dialogs.playlists.ImportPlaylistDialog
import com.mardous.booming.ui.screen.discover.SpotifyCardAdapter
import com.mardous.booming.ui.screen.discover.SpotifyPlaylistFragment
import com.mardous.booming.ui.screen.library.ReloadType
import com.mardous.booming.online.viewmodel.StreamingAuthViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * @author Christians M. A. (mardous)
 */
class PlaylistListFragment : AbsRecyclerViewCustomGridSizeFragment<RecyclerView.Adapter<*>, GridLayoutManager>(),
    IPlaylistCallback {

    override val titleRes: Int = R.string.playlists_label
    override val isShuffleVisible: Boolean = false
    override val emptyMessageRes: Int
        get() = R.string.no_device_playlists

    private val sectionsViewModel: PlaylistsSectionsViewModel by viewModel()
    private val authViewModel: StreamingAuthViewModel by activityViewModel()

    private var playlistAdapter: PlaylistAdapter? = null
    private var yourHeaderAdapter: SectionHeaderAdapter? = null
    private var likedHeaderAdapter: SectionHeaderAdapter? = null
    private var likedRowAdapter: LikedSongsRowAdapter? = null
    private var spotifyShelf: HorizontalListAdapter? = null
    private var spotifyCardAdapter: SpotifyCardAdapter? = null

    private var playlists: List<PlaylistWithSongs> = emptyList()
    private var likedTotal: Int? = null
    private var spotifyPlaylists: List<SpotifyPlaylist> = emptyList()
    private var spotifyConnected = false

    override val maxGridSize: Int
        get() = if (isLandscape) resources.getInteger(R.integer.max_playlist_columns_land)
        else resources.getInteger(R.integer.max_playlist_columns)

    override val itemLayoutRes: Int
        get() = if (isGridMode) R.layout.item_playlist
        else R.layout.item_list

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        libraryViewModel.getPlaylists().observe(viewLifecycleOwner) { list ->
            playlists = list
            playlistAdapter?.dataSet = list
            refreshSectionVisibility()
        }
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                authViewModel.authState.collect { state ->
                    spotifyConnected = state == SpotifyAuthState.CONNECTED
                    if (spotifyConnected) {
                        sectionsViewModel.load()
                    }
                    refreshSectionVisibility()
                }
            }
            launch {
                sectionsViewModel.likedTotal.collect { total ->
                    likedTotal = total
                    refreshSectionVisibility()
                }
            }
            launch {
                sectionsViewModel.spotifyPlaylists.collect { list ->
                    spotifyPlaylists = list
                    refreshSectionVisibility()
                }
            }
        }
    }

    private fun refreshSectionVisibility() {
        val hasLocal = playlists.isNotEmpty()
        val likedVisible = spotifyConnected && likedTotal != null
        val spotifyVisible = spotifyConnected && spotifyPlaylists.isNotEmpty()

        yourHeaderAdapter?.setVisible(hasLocal)
        likedHeaderAdapter?.setVisible(likedVisible)
        likedRowAdapter?.update(
            visible = likedVisible,
            subtitle = likedTotal?.let { getString(R.string.liked_songs_subtitle, it) }.orEmpty()
        )
        spotifyShelf?.setVisible(spotifyVisible)
        spotifyCardAdapter?.dataSet = mapToCards(spotifyPlaylists)
    }

    private fun mapToCards(list: List<SpotifyPlaylist>): List<SpotifyHomeItem.Playlist> {
        return list.map { playlist ->
            SpotifyHomeItem.Playlist(
                uri = playlist.uri ?: "spotify:playlist:${playlist.id}",
                id = playlist.id,
                name = playlist.name,
                description = playlist.description,
                imageUrl = playlist.thumbnailUrl(),
                ownerName = playlist.owner?.displayName
            )
        }
    }

    private fun openLikedSongs() {
        findNavController().navigate(
            R.id.nav_spotify_playlist,
            spotifyPlaylistArgs(SpotifyPlaylistFragment.LIKED_SONGS_ID)
        )
    }

    override fun onResume() {
        super.onResume()
        libraryViewModel.forceReload(ReloadType.Playlists)
    }

    override fun createLayoutManager(): GridLayoutManager {
        return GridLayoutManager(requireContext(), gridSize).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val concat = adapter as? ConcatAdapter ?: return spanCount
                    val adapterAndPosition = concat.getWrappedAdapterAndPosition(position)
                    return if (adapterAndPosition.first is PlaylistAdapter) 1 else spanCount
                }
            }
        }
    }

    override fun createAdapter(): RecyclerView.Adapter<*> {
        notifyLayoutResChanged(itemLayoutRes)
        val dataSet = playlistAdapter?.dataSet ?: ArrayList()
        playlistAdapter = PlaylistAdapter(mainActivity, dataSet, itemLayoutRes, this)
        yourHeaderAdapter = SectionHeaderAdapter(getString(R.string.playlists_section_your))
        likedHeaderAdapter = SectionHeaderAdapter(getString(R.string.liked_songs_label))
        likedRowAdapter = LikedSongsRowAdapter { openLikedSongs() }
        spotifyCardAdapter = SpotifyCardAdapter { playlistId ->
            findNavController().navigate(
                R.id.nav_spotify_playlist,
                spotifyPlaylistArgs(playlistId)
            )
        }
        spotifyCardAdapter?.dataSet = mapToCards(spotifyPlaylists)
        spotifyShelf = HorizontalListAdapter(
            getString(R.string.playlists_section_spotify),
            spotifyCardAdapter!!
        )

        val concat = ConcatAdapter(
            likedHeaderAdapter!!,
            likedRowAdapter!!,
            spotifyShelf!!,
            yourHeaderAdapter!!,
            playlistAdapter!!
        )
        refreshSectionVisibility()
        return concat
    }

    override fun playlistClick(playlist: PlaylistWithSongs) {
        findNavController().navigate(R.id.nav_playlist_detail, playlistDetailArgs(playlist.playlistEntity.playListId))
    }

    override fun playlistMenuItemClick(playlist: PlaylistWithSongs, menuItem: MenuItem): Boolean {
        return playlist.onPlaylistMenu(this, menuItem)
    }

    override fun playlistsMenuItemClick(playlists: List<PlaylistWithSongs>, menuItem: MenuItem) {
        playlists.onPlaylistsMenu(this, menuItem)
    }

    override fun onMediaContentChanged() {
        libraryViewModel.forceReload(ReloadType.Playlists)
    }

    override fun onFavoriteContentChanged() {
        libraryViewModel.forceReload(ReloadType.Playlists)
    }

    override fun onPause() {
        super.onPause()
        playlistAdapter?.actionMode?.finish()
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateMenu(menu, inflater)
        menu.removeItem(R.id.action_view_type)
        menu.add(0, R.id.action_new_playlist, 0, R.string.new_playlist_title)
        menu.add(0, R.id.action_import_playlist, 0, R.string.action_import_playlist)
        PlaylistSortMode.AllPlaylists.createMenu(menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (PlaylistSortMode.AllPlaylists.sortItemSelected(item)) {
            libraryViewModel.forceReload(ReloadType.Playlists)
            return true
        }
        return when (item.itemId) {
            R.id.action_new_playlist -> {
                CreatePlaylistDialog()
                    .show(childFragmentManager, "NEW_PLAYLIST")
                true
            }
            R.id.action_import_playlist -> {
                ImportPlaylistDialog().show(childFragmentManager, "IMPORT_PLAYLIST")
                true
            }
            else -> super.onMenuItemSelected(item)
        }
    }

    override fun getSavedViewType(): GridViewType {
        return GridViewType.Normal
    }

    override fun saveViewType(viewType: GridViewType) {}

    override fun getSavedGridSize(): Int {
        return sharedPreferences.getInt(GRID_SIZE, defaultGridSize)
    }

    override fun saveGridSize(newGridSize: Int) {
        sharedPreferences.edit { putInt(GRID_SIZE, newGridSize) }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onGridSizeChanged(isLand: Boolean, gridColumns: Int) {
        layoutManager?.spanCount = gridColumns
        adapter?.notifyDataSetChanged()
    }

    companion object {
        private const val GRID_SIZE = "playlists_grid_size"
    }
}
