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

package com.mardous.booming.ui.screen.library.playlists

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialArcMotion
import com.google.android.material.transition.MaterialContainerTransform
import com.h6ah4i.android.widget.advrecyclerview.animator.RefactoredDefaultItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.utils.WrapperAdapterUtils
import com.mardous.booming.R
import com.mardous.booming.core.model.sort.SortKey
import com.mardous.booming.core.sort.SongSortMode
import coil3.request.crossfade
import coil3.toBitmap
import com.mardous.booming.coil.playlistImage
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.data.mapper.searchFilter
import com.mardous.booming.data.mapper.toSongEntity
import com.mardous.booming.data.mapper.toSongs
import com.mardous.booming.data.mapper.toSongsEntity
import com.mardous.booming.data.model.Song
import com.mardous.booming.databinding.FragmentPlaylistDetailBinding
import com.mardous.booming.extensions.applyHorizontalWindowInsets
import com.mardous.booming.extensions.collectPlayerAccent
import com.mardous.booming.extensions.resources.applyHeaderAccentFromArt
import com.mardous.booming.extensions.resources.applyPlayerAccent
import com.google.android.material.button.MaterialButton
import com.mardous.booming.ui.component.views.MorphicIconButton
import com.mardous.booming.extensions.isLandscape
import com.mardous.booming.extensions.isNullOrEmpty
import com.mardous.booming.extensions.materialSharedAxis
import com.mardous.booming.extensions.media.isFavorites
import com.mardous.booming.extensions.media.playlistInfo
import com.mardous.booming.extensions.navigation.searchArgs
import com.mardous.booming.extensions.resources.createFastScroller
import com.mardous.booming.extensions.resources.surfaceColor
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.showToast
import com.mardous.booming.core.model.shuffle.OpenShuffleMode
import com.mardous.booming.online.spotify.PlaylistAccentCache
import com.mardous.booming.ui.ISongCallback
import com.mardous.booming.ui.adapters.song.PlaylistSongAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onPlaylistMenu
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
import com.mardous.booming.ui.dialogs.playlists.RemoveFromPlaylistDialog
import com.mardous.booming.util.Preferences
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

/**
 * @author Christians M. A. (mardous)
 */
class PlaylistDetailFragment : AbsMainActivityFragment(R.layout.fragment_playlist_detail),
    ISongCallback {

    private val arguments by navArgs<PlaylistDetailFragmentArgs>()
    private val detailViewModel by viewModel<PlaylistDetailViewModel> {
        parametersOf(arguments.playlistId)
    }

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    private var playlist: PlaylistWithSongs = PlaylistWithSongs.Empty
    private var currentSongs: List<Song> = emptyList()

    /**
     * Accent extracted from the playlist cover (null until the art
     * resolves). Mirrors the remote playlist pages: the cover accent
     * wins over the player accent while set.
     */
    private var headerAccent: Int? = null

    private var playlistSongAdapter: PlaylistSongAdapter? = null
    private var wrappedAdapter: RecyclerView.Adapter<*>? = null
    private var recyclerViewDragDropManager: RecyclerViewDragDropManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition = MaterialContainerTransform(requireContext(), true).apply {
            drawingViewId = R.id.fragment_container
            scrimColor = Color.TRANSPARENT
            setAllContainerColors(surfaceColor())
            setPathMotion(MaterialArcMotion())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlaylistDetailBinding.bind(view)

        setupButtons()
        setupRecyclerView()
        collectPlayerAccent { accent ->
            // Cover accent wins once the playlist art has been resolved
            // (mirrors the remote playlist pages); until then the player
            // accent keeps the buttons from flashing the theme color.
            if (headerAccent == null) {
                applyHeaderAccent(accent)
            }
            // Checked (selected) rows tint at bind time; rebind so they
            // don't keep the previous accent.
            playlistSongAdapter?.refreshCheckedRows()
        }

        materialSharedAxis(view)
        view.applyHorizontalWindowInsets()


        // Instant prefill from the cached cover accent (same pattern as
        // the remote playlist pages): no theme-color flash on reopen,
        // and the player accent never gets a chance to claim the
        // buttons first.
        PlaylistAccentCache.get(requireContext(), localAccentKey(arguments.playlistId))?.let { cached ->
            headerAccent = cached
            applyHeaderAccent(cached)
        }

        setSupportActionBar(binding.toolbar)
        //binding.collapsingAppBarLayout.setupStatusBarScrim(requireContext())

        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace())
        }

        detailViewModel.getPlaylist().observe(viewLifecycleOwner) { playlistWithSongs ->
            playlist = playlistWithSongs
            binding.header.title.text = playlist.playlistEntity.playlistName
            val description = playlist.playlistEntity.description
            if (!description.isNullOrEmpty()) {
                binding.header.description.text = description
                binding.header.description.isVisible = true
            } else {
                binding.header.description.text = null
                binding.header.description.isGone = true
            }
            binding.collapsingAppBarLayout.title = playlist.playlistEntity.playlistName
            val info = playlist.songs.toSongs().playlistInfo(requireContext())
            binding.header.subtitle.text = if (playlist.playlistEntity.isSynced) {
                getString(R.string.synced_playlist_tag) + " • " + info
            } else {
                info
            }
            // Synced playlists own their content: no drag reorder.
            if (playlist.playlistEntity.isSynced) {
                playlistSongAdapter?.setLockDrag(true)
            }
            applySongs()
            binding.header.image.playlistImage(playlist) {
                crossfade(false)
                listener(
                    onSuccess = { _, result ->
                        binding.header.applyHeaderAccentFromArt(result.image.toBitmap()) { accent ->
                            headerAccent = accent
                            PlaylistAccentCache.put(
                                requireContext(),
                                localAccentKey(playlist.playlistEntity.playListId),
                                accent
                            )
                        }
                    }
                )
            }
        }
        detailViewModel.getSongs().observe(viewLifecycleOwner) {
            binding.progressIndicator.hide()
            currentSongs = it.toSongs()
            applySongs()
        }
        detailViewModel.playlistExists().observe(viewLifecycleOwner) {
            if (!it) {
                findNavController().navigateUp()
            }
        }
    }

    private fun checkIsEmpty() {
        binding.empty.isVisible = playlistSongAdapter?.isNullOrEmpty == true
    }

    private fun applySongs() {
        playlistSongAdapter?.dataSet = sortedSongs(currentSongs)
    }

    /**
     * Applies the per-playlist sort choice. A null sortMode keeps the
     * manual (drag) order stored in the Room rows; otherwise the list is
     * sorted in memory, leaving the stored order untouched.
     */
    private fun sortedSongs(songs: List<Song>): List<Song> {
        val modeValue = playlist.playlistEntity.sortMode ?: return songs
        val sortKey = SortKey.entries.firstOrNull { it.value == modeValue } ?: return songs
        return with(
            SongSortMode.Dynamic(sortKey, playlist.playlistEntity.sortDescending)
        ) {
            songs.sorted()
        }
    }

    private fun setPlaylistSort(sortKey: SortKey?) {
        detailViewModel.setPlaylistSort(sortKey?.value, playlist.playlistEntity.sortDescending)
    }

    private fun applyHeaderAccent(accent: Int) {
        // playAction is a MorphicIconButton in portrait but a plain
        // MaterialButton in the landscape header variant, so the binding
        // types it as their common ancestor - branch on the runtime type.
        when (val playButton = binding.header.playAction) {
            is MorphicIconButton -> playButton.applyPlayerAccent(accent)
            is MaterialButton -> playButton.applyPlayerAccent(accent)
        }
        binding.header.shuffleAction.applyPlayerAccent(accent, tonal = true)
        binding.header.searchAction?.applyPlayerAccent(accent)
    }

    private fun localAccentKey(playlistId: Long): String = "local:$playlistId"

    private fun setupButtons() {
        binding.header.playAction.setOnClickListener {
            playlistSongAdapter?.dataSet?.let {
                playerViewModel.openQueue(it, shuffleMode = OpenShuffleMode.Off)
            }
        }
        binding.header.shuffleAction.setOnClickListener {
            playlistSongAdapter?.dataSet?.let {
                playerViewModel.openAndShuffleQueue(it)
            }
        }
        binding.header.searchAction?.setOnClickListener {
            findNavController().navigate(
                R.id.nav_search,
                searchArgs(playlist.playlistEntity.searchFilter(requireContext()))
            )
        }
    }

    private fun setupRecyclerView() {
        playlistSongAdapter = PlaylistSongAdapter(
            activity = mainActivity,
            dataSet = emptyList(),
            itemLayoutRes = R.layout.item_list,
            isLockDrag = Preferences.lockedPlaylists,
            callback = this
        )
        recyclerViewDragDropManager = RecyclerViewDragDropManager().also { dragDropManager ->
            wrappedAdapter = dragDropManager.createWrappedAdapter(playlistSongAdapter!!)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = wrappedAdapter
        binding.recyclerView.itemAnimator = RefactoredDefaultItemAnimator()
        binding.recyclerView.createFastScroller()
        recyclerViewDragDropManager?.attachRecyclerView(binding.recyclerView)
        playlistSongAdapter!!.registerAdapterDataObserver(adapterDataObserver)
    }

    private val adapterDataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            checkIsEmpty()
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_playlist_detail, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        playlist.let {
            if (it.playlistEntity.isFavorites(requireContext())) {
                menu.removeItem(R.id.action_delete_playlist)
            }
        }
        if (playlist.playlistEntity.isSynced) {
            // Synced playlists own their content: no manual additions.
            menu.removeItem(R.id.action_add_to_playlist)
        }
        menu.findItem(R.id.action_playlist_sort_order)?.subMenu?.let { sortMenu ->
            val entity = playlist.playlistEntity
            sortMenu.setGroupCheckable(0, true, true)
            sortMenu.findItem(R.id.action_playlist_sort_custom)?.isChecked =
                entity.sortMode == null
            sortMenu.findItem(R.id.action_sort_order_name)?.isChecked =
                entity.sortMode == SortKey.Name.value
            sortMenu.findItem(R.id.action_sort_order_artist)?.isChecked =
                entity.sortMode == SortKey.Artist.value
            sortMenu.findItem(R.id.action_sort_order_album)?.isChecked =
                entity.sortMode == SortKey.Album.value
            sortMenu.findItem(R.id.action_sort_order_duration)?.isChecked =
                entity.sortMode == SortKey.Duration.value
            sortMenu.findItem(R.id.action_sort_order_year)?.isChecked =
                entity.sortMode == SortKey.Year.value
            sortMenu.findItem(R.id.action_sort_order_date_added)?.isChecked =
                entity.sortMode == SortKey.DateAdded.value
            sortMenu.findItem(R.id.action_sort_order_descending)?.isChecked =
                entity.sortDescending
        }
        if (playlistSongAdapter?.isLockDrag == true) {
            menu.findItem(R.id.action_lock)
                ?.setIcon(R.drawable.ic_lock_24dp)
        } else {
            menu.findItem(R.id.action_lock)
                ?.setIcon(R.drawable.ic_lock_open_24dp)
        }
        if (!isLandscape()) {
            menu.removeItem(R.id.action_search)
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            android.R.id.home -> {
                findNavController().navigateUp()
                true
            }

            R.id.action_search -> {
                findNavController().navigate(
                    R.id.nav_search,
                    searchArgs(playlist.playlistEntity.searchFilter(requireContext()))
                )
                true
            }

            R.id.action_lock -> {
                val lockedPlaylists = !Preferences.lockedPlaylists
                Preferences.lockedPlaylists = lockedPlaylists
                if (lockedPlaylists) {
                    menuItem.setIcon(R.drawable.ic_lock_24dp)
                    showToast(R.string.playlist_locked)
                } else {
                    menuItem.setIcon(R.drawable.ic_lock_open_24dp)
                    showToast(R.string.playlist_unlocked)
                }
                playlistSongAdapter?.setLockDrag(lockedPlaylists)
                true
            }

            R.id.action_playlist_sort_custom -> {
                setPlaylistSort(null)
                true
            }

            R.id.action_sort_order_name -> {
                setPlaylistSort(SortKey.Name)
                true
            }

            R.id.action_sort_order_artist -> {
                setPlaylistSort(SortKey.Artist)
                true
            }

            R.id.action_sort_order_album -> {
                setPlaylistSort(SortKey.Album)
                true
            }

            R.id.action_sort_order_duration -> {
                setPlaylistSort(SortKey.Duration)
                true
            }

            R.id.action_sort_order_year -> {
                setPlaylistSort(SortKey.Year)
                true
            }

            R.id.action_sort_order_date_added -> {
                setPlaylistSort(SortKey.DateAdded)
                true
            }

            R.id.action_sort_order_descending -> {
                detailViewModel.setPlaylistSort(
                    playlist.playlistEntity.sortMode,
                    !playlist.playlistEntity.sortDescending
                )
                true
            }

            else -> playlist.onPlaylistMenu(this, menuItem)
        }
    }

    override fun songMenuItemClick(
        song: Song,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean {
        return when (menuItem.itemId) {
            R.id.action_remove_from_playlist -> {
                if (playlist.playlistEntity.isSynced) {
                    // Content is rule-owned; removal is not available.
                    true
                } else {
                    RemoveFromPlaylistDialog.create(song.toSongEntity(playlist.playlistEntity.playListId))
                        .show(childFragmentManager, "REMOVE_FROM_PLAYLIST")
                    true
                }
            }

            else -> song.onSongMenu(this, menuItem)
        }
    }

    override fun songsMenuItemClick(songs: List<Song>, menuItem: MenuItem) {
        when (menuItem.itemId) {
            R.id.action_remove_from_playlist -> {
                if (playlist.playlistEntity.isSynced) {
                    // Content is rule-owned; removal is not available.
                } else {
                    RemoveFromPlaylistDialog.create(songs.toSongsEntity(playlist.playlistEntity))
                        .show(childFragmentManager, "REMOVE_FROM_PLAYLIST")
                }
            }

            else -> songs.onSongsMenu(this, menuItem)
        }
    }

    override fun onPause() {
        recyclerViewDragDropManager?.cancelDrag()
        // Never persist the (possibly sorted) view order over a synced
        // playlist's rule-driven content.
        if (!playlist.playlistEntity.isSynced) {
            playlistSongAdapter?.saveSongs(playlist.playlistEntity)
        }
        super.onPause()
    }

    override fun onDestroyView() {
        playlistSongAdapter?.unregisterAdapterDataObserver(adapterDataObserver)

        recyclerViewDragDropManager?.release()
        recyclerViewDragDropManager = null

        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = null

        WrapperAdapterUtils.releaseAll(wrappedAdapter)
        wrappedAdapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PlaylistDetail"
    }
}