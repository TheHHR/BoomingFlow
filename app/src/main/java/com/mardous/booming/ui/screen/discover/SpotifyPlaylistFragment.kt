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
import com.mardous.booming.R
import com.mardous.booming.databinding.FragmentDetailListBinding
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.showToast
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.download.DownloadEvent
import com.mardous.booming.online.download.DownloadFormat
import com.mardous.booming.online.download.SongDownloader
import com.mardous.booming.online.spotify.resolveBatchForPlayback
import com.mardous.booming.online.spotify.resolveForPlayback
import com.mardous.booming.ui.IScrollHelper
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class SpotifyPlaylistFragment : AbsMainActivityFragment(R.layout.fragment_detail_list), IScrollHelper {

    private var _binding: FragmentDetailListBinding? = null
    private val binding get() = _binding!!

    private val playlistId: String?
        get() = arguments?.getString(ARG_PLAYLIST_ID)

    private val viewModel: SpotifyPlaylistViewModel by viewModel()
    private val songDownloader: SongDownloader by inject()

    private lateinit var trackAdapter: SpotifyTrackAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDetailListBinding.bind(view)

        setSupportActionBar(binding.toolbar, null)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        checkForMargins(binding.recyclerView)
        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace())
        }

        trackAdapter = SpotifyTrackAdapter(
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
            }
        )
        binding.recyclerView.adapter = trackAdapter

        binding.playAction.setOnClickListener { playAll(shuffle = false) }
        binding.shuffleAction.setOnClickListener { playAll(shuffle = true) }

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
                        binding.title.text = name
                        binding.subtitle.text = getString(
                            R.string.discover_playlist_subtitle,
                            playlist.owner?.displayName.orEmpty(),
                            playlist.tracks?.total ?: 0
                        )
                    }
                }
            }
            launch {
                viewModel.isLiked.collect { isLiked ->
                    if (isLiked) {
                        val label = getString(R.string.liked_songs_label)
                        setSupportActionBar(binding.toolbar, label)
                        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
                        binding.title.text = label
                    }
                }
            }
            launch {
                viewModel.tracks.collect { tracks ->
                    trackAdapter.dataSet = tracks
                    refreshDownloadedBadges()
                    if (viewModel.isLiked.value) {
                        binding.subtitle.text =
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
                    }
                }
            }
            launch {
                viewModel.loading.collect { loading ->
                    val hasTracks = trackAdapter.itemCount > 0
                    binding.progressIndicator.isVisible = loading
                    binding.recyclerView.isVisible = !loading && hasTracks
                    binding.empty.isVisible = !loading && !hasTracks
                }
            }
        }
    }

    private fun playTrackAt(index: Int) {
        val track = viewModel.tracks.value.getOrNull(index) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { track.resolveForPlayback() }
            if (item == null) {
                showToast(getString(R.string.no_youtube_match_found, track.name))
                return@launch
            }
            playerViewModel.playOnlineQueue(listOf(item))
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val batch = viewModel.tracks.value.drop(index + 1).resolveBatchForPlayback(30)
                playerViewModel.addOnlineQueue(batch)
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

    private fun refreshDownloadedBadges() {
        val downloadedIds = songDownloader.downloadedSpotifyTrackIds()
        trackAdapter.downloadedTrackIds = downloadedIds
        val tracks = viewModel.tracks.value
        val allDownloaded = tracks.isNotEmpty() && tracks.all { downloadedIds.contains(it.id) }
        downloadMenuItem?.setIcon(
            if (allDownloaded) R.drawable.ic_check_24dp else R.drawable.ic_download_24dp
        )
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
        return false
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
