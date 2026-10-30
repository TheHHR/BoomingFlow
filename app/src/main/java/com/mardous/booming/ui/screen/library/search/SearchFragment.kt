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

package com.mardous.booming.ui.screen.library.search

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.mardous.booming.core.model.theme.AccentSchemes
import com.mardous.booming.extensions.collectPlayerAccent
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.resources.applyPlayerAccent
import com.mardous.booming.extensions.resources.applyTogglePlayerAccent
import com.mardous.booming.R
import com.mardous.booming.data.SearchFilter
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.Genre
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.search.SearchQuery
import com.mardous.booming.databinding.FragmentSearchBinding
import com.mardous.booming.extensions.applyHorizontalWindowInsets
import com.mardous.booming.extensions.dip
import com.mardous.booming.extensions.hideSoftKeyboard
import com.mardous.booming.extensions.isEmpty
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.materialSharedAxis
import com.mardous.booming.extensions.navigation.albumDetailArgs
import com.mardous.booming.extensions.navigation.artistDetailArgs
import com.mardous.booming.extensions.navigation.asFragmentExtras
import com.mardous.booming.extensions.navigation.genreDetailArgs
import com.mardous.booming.extensions.navigation.playlistDetailArgs
import com.mardous.booming.extensions.navigation.spotifyPlaylistArgs
import com.mardous.booming.online.data.spotify.SpotifyAlbum
import com.mardous.booming.online.data.spotify.SpotifyArtist
import com.mardous.booming.online.data.spotify.SpotifyPlaylist
import com.mardous.booming.online.download.DownloadRegistry
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.spotify.resolveBatchForPlayback
import com.mardous.booming.online.spotify.resolveForPlayback
import com.mardous.booming.ui.screen.discover.SpotifyAlbumFragment
import com.mardous.booming.extensions.resources.focusAndShowKeyboard
import com.mardous.booming.extensions.resources.onVerticalScroll
import com.mardous.booming.extensions.resources.reactionToKey
import com.mardous.booming.extensions.resources.setupStatusBarForeground
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.showToast
import com.mardous.booming.ui.ISearchCallback
import com.mardous.booming.ui.adapters.SearchAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onAlbumMenu
import com.mardous.booming.ui.component.menu.onArtistMenu
import com.mardous.booming.ui.component.menu.onPlaylistMenu
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale

/**
 * @author Christians M. A. (mardous)
 */
class SearchFragment : AbsMainActivityFragment(R.layout.fragment_search),
    View.OnClickListener, MaterialButtonToggleGroup.OnButtonCheckedListener, ISearchCallback,
    com.mardous.booming.ui.ISpotifySearchCallback {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModel<SearchViewModel>()
    private val songDownloader: com.mardous.booming.online.download.SongDownloader by inject()

    private lateinit var searchAdapter: SearchAdapter

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSearchBinding.bind(view)
        setSupportActionBar(binding.toolbar)
        materialSharedAxis(view)
        view.applyHorizontalWindowInsets()

        arguments?.getString(SCOPE_PLAYLIST_KEY)?.let { scopeKey ->
            val scopeName = arguments?.getString(SCOPE_PLAYLIST_NAME)
            viewModel.setScope(scopeKey, scopeName)
        }
        if (viewModel.isScoped) {
            binding.filterScrollView.isVisible = false
            binding.searchView.hint = viewModel.scopePlaylistName
                ?: getString(R.string.search_hint)
        }

        setupRecyclerView()

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            viewModel.searchFilter.collect { searchFilter ->
                if (searchFilter != null) {
                    val compatibleModes = searchFilter.getCompatibleModes().map { it.buttonId }

                    binding.modeButtonGroup.children.map { it as MaterialButton }
                        .filter { !compatibleModes.contains(it.id) }
                        .forEach { it.isVisible = false }
                    binding.modeButtonGroup.isSelectionRequired = true
                    binding.modeButtonGroup.check(compatibleModes.first())

                    binding.searchView.hint = searchFilter.getName()
                    binding.filterScrollView.isVisible = searchFilter.getCompatibleModes().size > 1
                }
            }
        }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            combine(viewModel.searchResult, viewModel.spotifyResult) { local, spotify ->
                local to spotify
            }.collectLatest { (local, spotify) ->
                val spotifyItems = buildList {
                    if (spotify != null &&
                        (spotify.tracks.isNotEmpty() || spotify.playlists.isNotEmpty() ||
                            spotify.artists.isNotEmpty() || spotify.albums.isNotEmpty())
                    ) {
                        add(getString(R.string.spotify_section_title))
                        addAll(spotify.tracks)
                        addAll(spotify.playlists)
                        addAll(spotify.artists)
                        addAll(spotify.albums)
                    }
                }
                searchAdapter.dataSet = local + spotifyItems
            }
        }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            viewModel.queueFlow.collectLatest { (songs, startPos, songClickBehavior) ->
                playerViewModel.openSongs(startPos, songs, songClickBehavior)
                if (!songClickBehavior.isAbleToPlay) {
                    showToast(R.string.added_title_to_playing_queue)
                }
            }
        }

        // Observe playlists-related changes
        libraryViewModel.getPlaylists().observe(viewLifecycleOwner) {
            viewModel.refresh()
        }

        binding.appBar.setupStatusBarForeground()
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.clearText.setOnClickListener(this)
        binding.modeButtonGroup.addOnButtonCheckedListener(this)
        collectPlayerAccent { accent ->
            val scheme = if (accent != 0) {
                AccentSchemes.viewScheme(accent, requireContext().isNightMode)
            } else {
                null
            }
            for (child in binding.modeButtonGroup.children) {
                (child as? MaterialButton)?.applyTogglePlayerAccent(
                    accent,
                    scheme?.secondaryContainer ?: 0,
                    scheme?.onSecondaryContainer ?: 0
                )
            }
            binding.keyboardPopup.applyPlayerAccent(accent)
            // Badges and checked (selected) rows tint at bind time;
            // rebind so they don't keep the previous accent.
            searchAdapter.notifyDataSetChanged()
        }
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                DownloadRegistry.downloadedSpotifyIds.collect { ids ->
                    searchAdapter.downloadedSpotifyIds = ids
                }
            }
        }
        binding.searchView.apply {
            reactionToKey(KeyEvent.KEYCODE_ENTER) {
                hideSoftKeyboard()
            }
            doAfterTextChanged {
                search(it?.toString())
            }
            focusAndShowKeyboard()
        }
        binding.keyboardPopup.setOnClickListener {
            if (!searchAdapter.isInQuickSelectMode) {
                binding.searchView.focusAndShowKeyboard()
            }
        }

        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace(dip(R.dimen.fab_size_padding)))
        }

        libraryViewModel.getFabMargin().observe(viewLifecycleOwner) {
            binding.keyboardPopup.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = it.getWithSpace()
            }
        }

        KeyboardVisibilityEvent.setEventListener(requireActivity(), viewLifecycleOwner) {
            if (it) {
                binding.keyboardPopup.hide()
            } else {
                binding.keyboardPopup.show()
            }
        }

        if (savedInstanceState == null) {
            val filterMode = arguments?.let {
                BundleCompat.getSerializable(it, MODE, SearchQuery.FilterMode::class.java)
            }
            binding.modeButtonGroup.isSelectionRequired = true
            binding.modeButtonGroup.check(filterMode?.buttonId ?: SearchQuery.FilterMode.Songs.buttonId)
            binding.searchView.setText(arguments?.getString(QUERY))
            viewModel.updateFilter(arguments?.let {
                BundleCompat.getParcelable(it, FILTER, SearchFilter::class.java)
            })
        }
    }

    private val adapterDataObserver = object : AdapterDataObserver() {
        override fun onChanged() {
            super.onChanged()
            val isEmpty = searchAdapter.isEmpty
            if (!isEmpty) {
                binding.recyclerView.scheduleLayoutAnimation()
            }
            binding.empty.isVisible = isEmpty
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecyclerView() {
        searchAdapter = SearchAdapter(requireActivity(), emptyList(), this, this).apply {
            registerAdapterDataObserver(adapterDataObserver)
        }
        binding.recyclerView.apply {
            layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.layout_animation_fall_down)
                .apply { animation.duration = resources.getInteger(R.integer.short_anim_time).toLong() }
            layoutManager = LinearLayoutManager(activity)
            adapter = searchAdapter
            onVerticalScroll(viewLifecycleOwner,
                onScrollDown = { binding.keyboardPopup.shrink() },
                onScrollUp = { binding.keyboardPopup.extend() }
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val itemCount = recyclerView.adapter?.itemCount ?: return
                    if (lm.findLastVisibleItemPosition() >= itemCount - 6) {
                        viewModel.loadMoreSpotify()
                    }
                }
            })
        }
    }

    override fun onClick(view: View) {
        when (view) {
            binding.clearText -> binding.searchView.text?.clear()
        }
    }

    override fun onButtonChecked(
        group: MaterialButtonToggleGroup,
        checkedId: Int,
        isChecked: Boolean
    ) {
        val searchMode = SearchQuery.FilterMode.entries
            .firstOrNull { it.buttonId == group.checkedButtonId }
        viewModel.updateQuery(mode = searchMode)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            android.R.id.home -> {
                findNavController().navigateUp()
                true
            }

            else -> false
        }
    }

    override fun songClick(song: Song, results: List<Any>) {
        viewModel.songClick(song, results)
    }

    override fun songMenuItemClick(song: Song, menuItem: MenuItem): Boolean {
        return song.onSongMenu(this, menuItem)
    }

    private fun handleOnlineSongsMenu(menuItem: MenuItem, songs: List<com.mardous.booming.data.model.Song>) {
        val tracks = songs.mapNotNull { song ->
            viewModel.searchResult.value.filterIsInstance<SpotifyTrack>()
                .firstOrNull { it.id == song.data.removePrefix("yt:") }
        }
        when (menuItem.itemId) {
            R.id.action_play_next, R.id.action_add_to_playing_queue -> {
                viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
                    val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        tracks.mapNotNull { it.resolveForPlayback() }
                    }
                    if (items.isNotEmpty()) {
                        if (menuItem.itemId == R.id.action_play_next) {
                            playerViewModel.playOnlineQueueNext(items)
                            showToast(R.string.queue_added_next)
                        } else {
                            playerViewModel.addOnlineQueue(items)
                            showToast(R.string.queue_added)
                        }
                    }
                }
            }

            R.id.action_add_to_playlist -> {
                com.mardous.booming.ui.dialogs.playlists.AddToPlaylistDialog.create(songs)
                    .show(childFragmentManager, "ADD_TO_PLAYLIST")
            }

            else -> showToast(R.string.remote_action_unavailable)
        }
    }

    override fun albumClick(album: Album, sharedElements: Array<Pair<View, String>>) {
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
        sharedElements: Array<Pair<View, String>>
    ): Boolean {
        return album.onAlbumMenu(this, menuItem)
    }

    override fun artistClick(artist: Artist, sharedElements: Array<Pair<View, String>>) {
        findNavController().navigate(
            R.id.nav_artist_detail,
            artistDetailArgs(artist),
            null,
            sharedElements.asFragmentExtras()
        )
    }

    override fun artistMenuItemClick(
        artist: Artist,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>
    ): Boolean {
        return artist.onArtistMenu(this, menuItem)
    }

    override fun playlistClick(playlist: PlaylistWithSongs) {
        findNavController().navigate(R.id.nav_playlist_detail, playlistDetailArgs(playlist.playlistEntity.playListId))
    }

    override fun playlistMenuItemClick(playlist: PlaylistWithSongs, menuItem: MenuItem): Boolean {
        return playlist.onPlaylistMenu(this, menuItem)
    }

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<Song>) {
        val onlineSongs = selection.filter { it.data.startsWith("yt:") }
        val localSongs = selection.filterNot { it.data.startsWith("yt:") }
        if (onlineSongs.isNotEmpty()) {
            handleOnlineSongsMenu(menuItem, onlineSongs)
        }
        if (localSongs.isNotEmpty()) {
            localSongs.onSongsMenu(this, menuItem)
        }
    }

    override fun genreClick(genre: Genre) {
        findNavController().navigate(R.id.nav_genre_detail, genreDetailArgs(genre))
    }

    // ── Spotify results ─────────────────────────────────────────────────────

    override fun spotifyTrackClick(track: SpotifyTrack, tracks: List<SpotifyTrack>) {
        spotifyTrackPlay(track, tracks)
    }

    override fun spotifyTrackPlay(track: SpotifyTrack, tracks: List<SpotifyTrack>) {
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            val item = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                track.resolveForPlayback()
            }
            if (item == null) {
                showToast(getString(R.string.no_youtube_match_found, track.name))
                return@launchAndRepeatWithViewLifecycle
            }
            playerViewModel.playOnlineQueue(listOf(item))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val rest = tracks.dropWhile { it.id != track.id }.drop(1)
                playerViewModel.addOnlineQueue(rest.resolveBatchForPlayback(20))
            }
        }
    }

    override fun spotifyTrackPlayNext(track: SpotifyTrack) {
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            val item = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                track.resolveForPlayback()
            }
            if (item == null) {
                showToast(getString(R.string.no_youtube_match_found, track.name))
            } else {
                playerViewModel.playOnlineQueueNext(listOf(item))
                showToast(R.string.queue_added_next)
            }
        }
    }

    override fun spotifyTrackEnqueue(track: SpotifyTrack) {
        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            val item = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                track.resolveForPlayback()
            }
            if (item == null) {
                showToast(getString(R.string.no_youtube_match_found, track.name))
            } else {
                playerViewModel.addOnlineQueue(listOf(item))
                showToast(R.string.queue_added)
            }
        }
    }

    override fun spotifyTrackDownload(track: SpotifyTrack) {
        songDownloader.enqueue(listOf(track))
        showToast(getString(R.string.download_queued_toast, 1))
    }

    override fun spotifyTrackAddToPlaylist(track: SpotifyTrack) {
        com.mardous.booming.ui.dialogs.playlists.AddToPlaylistDialog.create(
            listOf(
                com.mardous.booming.data.model.UnindexedSong(
                    uri = android.net.Uri.parse("https://open.spotify.com"),
                    id = track.id.hashCode().toLong(),
                    data = "yt:${track.id}",
                    title = track.name,
                    size = 0,
                    duration = track.durationMs.toLong(),
                    albumName = track.album?.name.orEmpty(),
                    artistName = track.artists.joinToString { it.name }
                )
            )
        ).show(childFragmentManager, "ADD_TO_PLAYLIST")
    }

    override fun spotifyTrackGoToAlbum(track: SpotifyTrack) {
        val album = track.album ?: return
        findNavController().navigate(
            R.id.nav_spotify_album,
            com.mardous.booming.ui.screen.discover.SpotifyAlbumFragment.args(
                com.mardous.booming.online.data.spotify.SpotifyAlbum(
                    id = album.id,
                    name = album.name,
                    artistName = track.artists.joinToString { it.name },
                    year = null,
                    imageUrl = album.images.maxByOrNull { (it.width ?: 0) }?.url
                )
            )
        )
    }

    override fun spotifyTrackGoToArtist(track: SpotifyTrack) {
        val artist = track.artists.firstOrNull { it.id?.isNotBlank() == true } ?: return
        findNavController().navigate(
            R.id.nav_spotify_artist,
            Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
            }
        )
    }

    override fun spotifyPlaylistClick(playlist: SpotifyPlaylist) {
        findNavController().navigate(R.id.nav_spotify_playlist, spotifyPlaylistArgs(playlist.id))
    }

    override fun spotifyArtistClick(artist: SpotifyArtist) {
        findNavController().navigate(
            R.id.nav_spotify_artist,
            Bundle().apply {
                putString("artistId", artist.id)
                putString("artistName", artist.name)
                putString("artistImageUrl", artist.imageUrl)
            }
        )
    }

    override fun spotifyAlbumClick(album: SpotifyAlbum) {
        findNavController().navigate(R.id.nav_spotify_album, SpotifyAlbumFragment.args(album))
    }

    private fun search(query: String?) {
        if (query == null) return
        TransitionManager.beginDelayedTransition(binding.appBar)
        binding.clearText.isVisible = query.isNotEmpty()
        viewModel.updateQuery(query = query)
    }

    private fun hideSoftKeyboard() {
        activity?.hideSoftKeyboard()
        binding.searchView.clearFocus()
    }

    override fun onMediaContentChanged() {
        super.onMediaContentChanged()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        hideSoftKeyboard()
        super.onDestroyView()
        searchAdapter.unregisterAdapterDataObserver(adapterDataObserver)
        binding.modeButtonGroup.removeOnButtonCheckedListener(this)
        binding.searchView.setOnKeyListener(null)
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        searchAdapter.actionMode?.finish()
    }

    companion object {
        const val MODE = "mode"
        const val QUERY = "query"
        const val SCOPE_PLAYLIST_KEY = "scopePlaylistKey"
        const val SCOPE_PLAYLIST_NAME = "scopePlaylistName"
        private const val FILTER = "filter"
    }
}