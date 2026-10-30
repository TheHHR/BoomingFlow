package com.mardous.booming.ui.screen.discover

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.mardous.booming.R
import com.mardous.booming.coil.DEFAULT_PLAYLIST_IMAGE
import com.mardous.booming.coil.placeholderDrawableRes
import com.mardous.booming.core.PlayerAccentState
import com.mardous.booming.databinding.FragmentPlaylistDetailBinding
import com.mardous.booming.extensions.collectPlayerAccent
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.resources.applyPlayerAccent
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.showToast
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.data.spotify.thumbnailUrl
import com.mardous.booming.online.download.DownloadEvent
import com.mardous.booming.online.download.DownloadFormat
import com.mardous.booming.online.download.DownloadRegistry
import com.mardous.booming.online.download.SongDownloader
import com.mardous.booming.online.spotify.resolveBatchForPlayback
import com.mardous.booming.online.spotify.resolveForPlayback
import com.mardous.booming.ui.IScrollHelper
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.views.MorphicIconButton
import com.mardous.booming.util.Preferences
import coil3.load
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class SpotifyPlaylistFragment : AbsMainActivityFragment(R.layout.fragment_playlist_detail), IScrollHelper {

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    private val playlistId: String?
        get() = arguments?.getString(ARG_PLAYLIST_ID)

    private val viewModel: SpotifyPlaylistViewModel by viewModel()
    private val songDownloader: SongDownloader by inject()

    private lateinit var trackAdapter: SpotifyTrackAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlaylistDetailBinding.bind(view)

        setSupportActionBar(binding.toolbar, null)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        checkForMargins(binding.recyclerView)
        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace())
        }

        trackAdapter = SpotifyTrackAdapter(
            requireActivity(),
            onTrackClick = { index -> playTrackAt(index) },
            onPlayNext = { index ->
                viewModel.tracks.value.getOrNull(index)?.let { track ->
                    resolveAndPlayNext(track)
                }
            },
            onAddToQueue = { index ->
                viewModel.tracks.value.getOrNull(index)?.let { track ->
                    resolveAndEnqueue(track)
                }
            },
            onDownload = { index ->
                viewModel.tracks.value.getOrNull(index)?.let { track ->
                    startDownload(listOf(track), DownloadFormat.M4A)
                }
            },
            onMultipleItemsAction = { menuItem, songs ->
                handleOnlineSongsMenu(menuItem, songs)
            }
        )
        binding.recyclerView.adapter = trackAdapter

        binding.header.playAction.setOnClickListener { playAll(shuffle = false) }
        binding.header.shuffleAction.setOnClickListener { playAll(shuffle = true) }
        binding.header.searchAction?.setOnClickListener { openScopedSearch() }
        collectPlayerAccent { accent ->
            // playAction is a MorphicIconButton in the header layout; the
            // branch mirrors the local playlist detail for safety.
            when (val playButton = binding.header.playAction) {
                is MorphicIconButton -> playButton.applyPlayerAccent(accent)
                is MaterialButton -> playButton.applyPlayerAccent(accent)
            }
            binding.header.shuffleAction.applyPlayerAccent(accent, tonal = true)
            binding.header.searchAction?.applyPlayerAccent(accent)
            binding.progressIndicator.applyPlayerAccent(accent)
            // The download-menu action view (inflated separately) follows
            // the accent too; it re-inflates per menu rebuild, so tint it
            // here as well.
            downloadMenuItem?.actionView
                ?.findViewById<CircularProgressIndicator>(R.id.action_progress)
                ?.applyPlayerAccent(accent)
            // Badge tints are applied at bind time - rebind so they follow.
            trackAdapter.notifyDataSetChanged()
        }

        val id = playlistId
        viewModel.load(if (id == LIKED_SONGS_ID) null else id)

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                viewModel.playlist.collect { playlist ->
                    if (playlist != null) {
                        val name = playlist.name.ifBlank {
                            playlist.description?.ifBlank { null } ?: playlist.owner?.displayName.orEmpty()
                        }
                        setSupportActionBar(binding.toolbar, name)
                        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
                        binding.collapsingAppBarLayout.title = name
                        binding.header.title.text = name
                        binding.header.subtitle.text = getString(
                            R.string.discover_playlist_subtitle,
                            playlist.owner?.displayName.orEmpty(),
                            playlist.tracks?.total ?: 0
                        )
                        binding.header.image.load(playlist.thumbnailUrl() ?: DEFAULT_PLAYLIST_IMAGE) {
                            placeholderDrawableRes(requireContext(), DEFAULT_PLAYLIST_IMAGE)
                        }
                    }
                }
            }
            launch {
                viewModel.isLiked.collect { isLiked ->
                    if (isLiked) {
                        val label = getString(R.string.liked_songs_label)
                        setSupportActionBar(binding.toolbar, label)
                        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
                        binding.collapsingAppBarLayout.title = label
                        binding.header.title.text = label
                        binding.header.image.load(DEFAULT_PLAYLIST_IMAGE) {
                            placeholderDrawableRes(requireContext(), DEFAULT_PLAYLIST_IMAGE)
                        }
                    }
                }
            }
            launch {
                viewModel.tracks.collect { tracks ->
                    refreshDownloadedBadges()
                    applyTracks()
                    maybeAutoDownload(tracks)
                    if (viewModel.isLiked.value) {
                        binding.header.subtitle.text =
                            getString(R.string.liked_songs_subtitle, tracks.size)
                    }
                }
            }
            launch {
                songDownloader.activeCount.collect { active ->
                    updateDownloadMenu(active > 0)
                }
            }
            launch {
                songDownloader.events.collect { event ->
                    when (event) {
                        is DownloadEvent.Completed -> refreshDownloadedBadges()
                        is DownloadEvent.Failed -> showToast(
                            getString(R.string.download_failed_track, eventTitle(event.videoId))
                        )
                        else -> Unit
                    }
                }
            }
            launch {
                DownloadRegistry.downloadedSpotifyIds.collect { ids ->
                    trackAdapter.downloadedTrackIds = ids
                }
            }
            launch {
                viewModel.loading.collect { loading ->
                    applyTracks()
                }
            }
        }
    }

    private fun playTrackAt(index: Int) {
        val tracks = viewModel.tracks.value
        val track = tracks.getOrNull(index) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { track.resolveForPlayback() }
            if (item == null) {
                showToast(getString(R.string.no_youtube_match_found, track.name))
                return@launch
            }
            playerViewModel.playOnlineQueue(listOf(item))
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val batch = tracks.drop(index + 1).resolveBatchForPlayback(30)
                playerViewModel.addOnlineQueue(batch)
            }
            // Queue the tracks BEFORE the tapped one, inserted at the front in
            // order, so the queue mirrors local playlists: whole list queued,
            // tapped song playing.
            if (index > 0) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    var insertIndex = 0
                    tracks.take(index).chunked(30).forEach { chunk ->
                        val items = chunk.resolveBatchForPlayback(30)
                        if (items.isNotEmpty()) {
                            playerViewModel.addOnlineQueueAt(insertIndex, items)
                            insertIndex += items.size
                        }
                    }
                }
            }
        }
    }

    private fun resolveAndPlayNext(track: SpotifyTrack) {
        viewLifecycleOwner.lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { track.resolveForPlayback() }
            if (item == null) {
                showToast(getString(R.string.no_youtube_match_found, track.name))
            } else {
                playerViewModel.playOnlineQueueNext(listOf(item))
                showToast(R.string.queue_added_next)
            }
        }
    }

    private fun resolveAndEnqueue(track: SpotifyTrack) {
        viewLifecycleOwner.lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { track.resolveForPlayback() }
            if (item == null) {
                showToast(getString(R.string.no_youtube_match_found, track.name))
            } else {
                playerViewModel.addOnlineQueue(listOf(item))
                showToast(R.string.queue_added)
            }
        }
    }

    private fun handleOnlineSongsMenu(menuItem: MenuItem, songs: List<com.mardous.booming.data.model.Song>) {
        val tracks = songs.mapNotNull { song ->
            viewModel.tracks.value.firstOrNull { it.id == song.data.removePrefix("yt:") }
        }
        when (menuItem.itemId) {
            R.id.action_play_next, R.id.action_add_to_playing_queue -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    val items = withContext(Dispatchers.IO) { tracks.mapNotNull { it.resolveForPlayback() } }
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

    private fun playAll(shuffle: Boolean) {
        val tracks = viewModel.tracks.value
        if (tracks.isEmpty()) return
        val ordered = if (shuffle) tracks.shuffled() else tracks
        viewLifecycleOwner.lifecycleScope.launch {
            val first = withContext(Dispatchers.IO) { ordered.first().resolveForPlayback() }
            if (first == null) {
                showToast(getString(R.string.no_youtube_match_found, ordered.first().name))
                return@launch
            }
            playerViewModel.playOnlineQueue(listOf(first))
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                playerViewModel.addOnlineQueue(ordered.drop(1).resolveBatchForPlayback(30))
            }
        }
    }

    val downloadableTracks: List<SpotifyTrack>
        get() = viewModel.tracks.value

    val playlistKey: String
        get() = playlistId ?: "liked"

    fun startAutoDownload() {
        songDownloader.enqueueMissing(viewModel.tracks.value)
    }

    private fun maybeAutoDownload(tracks: List<SpotifyTrack>) {
        if (tracks.isEmpty()) return
        if (!DownloadRegistry.autoDownload(requireContext(), playlistKey)) return
        songDownloader.enqueueMissing(tracks)
    }

    private fun refreshDownloadedBadges() {
        val downloadedIds = songDownloader.downloadedSpotifyTrackIds()
        trackAdapter.downloadedTrackIds = downloadedIds
        val tracks = viewModel.tracks.value
        val allDownloaded = tracks.isNotEmpty() && tracks.all { downloadedIds.contains(it.id) }
        downloadMenuItem?.setIcon(
            if (allDownloaded) R.drawable.ic_check_24dp else R.drawable.ic_download_24dp
        )
    }

    private fun applyTracks() {
        val tracks = viewModel.tracks.value
        trackAdapter.dataSet = tracks
        val loading = viewModel.loading.value
        binding.progressIndicator.isVisible = loading
        binding.recyclerView.isVisible = !loading && tracks.isNotEmpty()
        binding.empty.isVisible = !loading && tracks.isEmpty()
    }

    private fun eventTitle(videoId: String): String =
        songDownloader.progress.value[videoId]?.title ?: videoId

    private var downloadMenuItem: MenuItem? = null

    private fun updateDownloadMenu(downloading: Boolean) {
        val item = downloadMenuItem ?: return
        if (downloading) {
            if (item.actionView == null) {
                val actionView = layoutInflater.inflate(R.layout.action_view_download_progress, null)
                actionView.setOnClickListener { openDownloadsManager() }
                if (Preferences.followPlayerColor) {
                    actionView.findViewById<CircularProgressIndicator>(R.id.action_progress)
                        ?.applyPlayerAccent(PlayerAccentState.accentColor.value)
                }
                item.actionView = actionView
            }
        } else {
            if (item.actionView != null) {
                item.actionView = null
            }
        }
    }

    private fun openDownloadsManager() {
        DownloadsManagerSheet().show(childFragmentManager, "downloads_manager")
    }

    fun startDownload(tracks: List<SpotifyTrack>, format: DownloadFormat) {
        if (tracks.isEmpty()) return
        songDownloader.enqueue(tracks, format)
        showToast(getString(R.string.download_queued_toast, tracks.size))
    }

    private fun openDownloadSheet() {
        if (viewModel.tracks.value.isEmpty()) return
        PlaylistDownloadSheet().show(childFragmentManager, "playlist_download_sheet")
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_spotify_playlist, menu)
        downloadMenuItem = menu.findItem(R.id.action_download)
        if (songDownloader.activeCount.value > 0) {
            updateDownloadMenu(true)
        }
        refreshDownloadedBadges()
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_download) {
            if (songDownloader.activeCount.value > 0) {
                openDownloadsManager()
            } else {
                openDownloadSheet()
            }
            return true
        }
        if (menuItem.itemId == R.id.action_search) {
            openScopedSearch()
            return true
        }
        return false
    }

    private fun openScopedSearch() {
        val name = viewModel.playlist.value?.name
            ?: if (viewModel.isLiked.value) getString(R.string.liked_songs_label) else null
        findNavController().navigate(
            R.id.nav_search,
            Bundle().apply {
                putString(
                    com.mardous.booming.ui.screen.library.search.SearchFragment.SCOPE_PLAYLIST_KEY,
                    playlistKey
                )
                putString(
                    com.mardous.booming.ui.screen.library.search.SearchFragment.SCOPE_PLAYLIST_NAME,
                    name
                )
            }
        )
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_PLAYLIST_ID = "playlistId"
        const val LIKED_SONGS_ID = "liked"
    }
}
