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
import com.mardous.booming.core.model.shuffle.OpenShuffleMode
import com.mardous.booming.online.spotify.PlaylistAccentCache
import com.mardous.booming.ui.ISongCallback
import com.mardous.booming.ui.adapters.song.PlaylistSongAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onPlaylistMenu
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
import com.mardous.booming.ui.dialogs.playlists.RemoveFromPlaylistDialog
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
    private var playlistSortMode: SongSortMode.PlaylistSongs? = null

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
            playlistSortMode = buildPlaylistSortMode()
            // Drag reorder exists only in Custom order. Synced playlists
            // allow it too: the sync pass is differential, so the manual
            // order of kept rows survives (new rule matches append).
            playlistSongAdapter?.setCanReorder(playlist.playlistEntity.sortMode == null)
            playlistSongAdapter?.setPlaylistSynced(playlist.playlistEntity.isSynced)
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

    /**
     * Entity-backed sort mode: menu checks update instantly and every
     * selection is persisted to the playlist entity through the callback.
     * Switching away from Custom order saves the manual (drag) order
     * first, or it is lost once the sorted view replaces the dataSet.
     */
    private fun buildPlaylistSortMode(): SongSortMode.PlaylistSongs {
        val entity = playlist.playlistEntity
        val initialKey = entity.sortMode?.let { mode ->
            SortKey.entries.firstOrNull { it.value == mode }
        }
        return SongSortMode.PlaylistSongs(initialKey, entity.sortDescending) { sortKey, descending ->
            if (entity.sortMode == null && sortKey != null) {
                playlistSongAdapter?.saveSongs(entity)
            }
            detailViewModel.setPlaylistSort(sortKey?.value, descending)
        }
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

    private fun openScopedSearch() {
        // The playlist loads asynchronously; tapping search before the
        // first emission would build the filter with Empty's id (-1),
        // silently searching nothing.
        if (playlist == PlaylistWithSongs.Empty) return
        findNavController().navigate(
            R.id.nav_search,
            searchArgs(playlist.playlistEntity.searchFilter(requireContext()))
        )
    }

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
            openScopedSearch()
        }
    }

    private fun setupRecyclerView() {
        playlistSongAdapter = PlaylistSongAdapter(
            activity = mainActivity,
            dataSet = emptyList(),
            itemLayoutRes = R.layout.item_list,
            callback = this,
            onOrderChanged = {
                // Persist at drag end (queue-style); onPause stays as a
                // backstop. Only in Custom order, never for Empty.
                val entity = playlist.playlistEntity
                if (playlist != PlaylistWithSongs.Empty && entity.sortMode == null) {
                    playlistSongAdapter?.saveSongs(entity)
                }
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = playlistSongAdapter
        binding.recyclerView.createFastScroller()
        playlistSongAdapter!!.attachToRecyclerView(binding.recyclerView)
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
        if (playlistSortMode == null) {
            playlistSortMode = buildPlaylistSortMode()
        }
        playlistSortMode?.createMenu(menu)
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
                openScopedSearch()
                true
            }

            else -> {
                if (playlistSortMode?.sortItemSelected(menuItem) == true) {
                    // Sort state is checked + persisted inside the engine.
                    true
                } else {
                    playlist.onPlaylistMenu(this, menuItem)
                }
            }
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
        // Persist the manual (drag) order in Custom order — synced or
        // not (the sync pass preserves kept rows). Never while a sort
        // is active: the dataSet is the sorted view then.
        if (playlist.playlistEntity.sortMode == null) {
            playlistSongAdapter?.saveSongs(playlist.playlistEntity)
        }
        super.onPause()
    }

    override fun onDestroyView() {
        playlistSongAdapter?.unregisterAdapterDataObserver(adapterDataObserver)
        binding.recyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "PlaylistDetail"
    }
}