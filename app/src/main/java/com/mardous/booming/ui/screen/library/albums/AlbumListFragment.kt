/*
 * Copyright (c) 2024 Christians Martínez Alvarado
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

package com.mardous.booming.ui.screen.library.albums

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.content.edit
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.mardous.booming.R
import com.mardous.booming.core.model.GridViewType
import com.mardous.booming.core.model.sort.SortKey
import com.mardous.booming.core.sort.AlbumSortMode
import com.mardous.booming.core.sort.SongSortMode
import com.mardous.booming.data.model.Album
import com.mardous.booming.extensions.navigation.albumDetailArgs
import com.mardous.booming.extensions.navigation.asFragmentExtras
import com.mardous.booming.extensions.showToast
import com.mardous.booming.ui.IAlbumCallback
import com.mardous.booming.ui.adapters.FusedLibraryAdapter
import com.mardous.booming.ui.adapters.album.AlbumAdapter
import com.mardous.booming.ui.component.base.AbsRecyclerViewCustomGridSizeFragment
import com.mardous.booming.ui.component.menu.onAlbumMenu
import com.mardous.booming.ui.component.menu.onAlbumsMenu
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.ui.screen.library.ReloadType
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.launch

class AlbumListFragment : AbsRecyclerViewCustomGridSizeFragment<FusedLibraryAdapter, GridLayoutManager>(),
    IAlbumCallback {

    override val titleRes: Int = R.string.albums_label
    override val isShuffleVisible: Boolean = true
    override val emptyMessageRes: Int
        get() = R.string.no_albums_label

    private lateinit var fusedAdapter: FusedLibraryAdapter
    private val sectionsViewModel: com.mardous.booming.ui.screen.discover.SpotifyLibrarySectionsViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        libraryViewModel.getAlbums().observe(viewLifecycleOwner) { albums ->
            (fusedAdapter.localAdapter as? AlbumAdapter)?.dataSet = albums
        }
        sectionsViewModel.load()
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                sectionsViewModel.albums.collect { items ->
                    fusedAdapter.spotifyItems = items
                }
            }
        }
    }

    override fun onShuffleClicked() {
        super.onShuffleClicked()
        (fusedAdapter.localAdapter as? AlbumAdapter)?.dataSet?.let {
            playerViewModel.openShuffle(
                providers = it,
                mode = Preferences.albumShuffleMode,
                sortMode = SongSortMode.Dynamic(SortKey.Track)
            ).observe(viewLifecycleOwner) { success ->
                if (success) {
                    showToast(R.string.albums_shuffle)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        libraryViewModel.forceReload(ReloadType.Albums)
    }

    override fun onPause() {
        super.onPause()
        (fusedAdapter.localAdapter as? AlbumAdapter)?.actionMode?.finish()
    }

    override fun createLayoutManager(): GridLayoutManager {
        val manager = GridLayoutManager(activity, gridSize)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (::fusedAdapter.isInitialized) {
                    fusedAdapter.spanSizeFor(position, manager.spanCount)
                } else {
                    1
                }
        }
        return manager
    }

    override fun createAdapter(): FusedLibraryAdapter {
        val itemLayoutRes = itemLayoutRes
        notifyLayoutResChanged(itemLayoutRes)
        val dataSet: List<Album> = if (::fusedAdapter.isInitialized) {
            (fusedAdapter.localAdapter as? AlbumAdapter)?.dataSet ?: ArrayList()
        } else {
            ArrayList()
        }
        fusedAdapter = FusedLibraryAdapter(
            headerTitle = getString(R.string.spotify_section_title),
            localAdapter = AlbumAdapter(
                activity = mainActivity,
                dataSet = dataSet,
                itemLayoutRes = itemLayoutRes,
                sortMode = AlbumSortMode.AllAlbums,
                callback = this
            )
        ) { item ->
            val album = item as? com.mardous.booming.online.data.spotify.SpotifyHomeItem.Album
                ?: return@FusedLibraryAdapter
            findNavController().navigate(
                R.id.nav_spotify_album,
                com.mardous.booming.ui.screen.discover.SpotifyAlbumFragment.args(
                    com.mardous.booming.online.data.spotify.SpotifyAlbum(
                        id = album.id,
                        name = album.name,
                        artistName = album.artists.joinToString { it.name },
                        year = null,
                        imageUrl = album.imageUrl
                    )
                )
            )
        }
        return fusedAdapter
    }

    override fun albumClick(album: Album, sharedElements: Array<Pair<View, String>>?) {
        findNavController().navigate(
            R.id.nav_album_detail,
            albumDetailArgs(album.id),
            null,
            sharedElements.asFragmentExtras()
        )
    }

    override fun albumMenuItemClick(
        album: Album,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean {
        return album.onAlbumMenu(this, menuItem)
    }

    override fun albumsMenuItemClick(albums: List<Album>, menuItem: MenuItem) {
        albums.onAlbumsMenu(this, menuItem)
    }

    override fun getSavedViewType(): GridViewType {
        return GridViewType.entries.firstOrNull {
            it.name == sharedPreferences.getString(VIEW_TYPE, null)
        } ?: GridViewType.Normal
    }

    override fun saveViewType(viewType: GridViewType) {
        sharedPreferences.edit { putString(VIEW_TYPE, viewType.name) }
    }

    override fun getSavedGridSize(): Int {
        return sharedPreferences.getInt(GRID_SIZE, defaultGridSize)
    }

    override fun saveGridSize(newGridSize: Int) {
        sharedPreferences.edit { putInt(GRID_SIZE, newGridSize) }
    }

    override fun onGridSizeChanged(isLand: Boolean, gridColumns: Int) {
        layoutManager?.spanCount = gridColumns
        fusedAdapter.notifyDataSetChanged()
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateMenu(menu, inflater)
        AlbumSortMode.AllAlbums.createMenu(menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (AlbumSortMode.AllAlbums.sortItemSelected(item)) {
            libraryViewModel.forceReload(ReloadType.Albums)
            return true
        }
        return super.onMenuItemSelected(item)
    }

    override fun onMediaContentChanged() {
        super.onMediaContentChanged()
        libraryViewModel.forceReload(ReloadType.Albums)
    }

    companion object {
        private const val VIEW_TYPE = "albums_view_type"
        private const val GRID_SIZE = "albums_grid_size"
    }
}