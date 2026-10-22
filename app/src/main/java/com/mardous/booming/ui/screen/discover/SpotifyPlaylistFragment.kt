package com.mardous.booming.ui.screen.discover

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.mardous.booming.R
import com.mardous.booming.databinding.FragmentDetailListBinding
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.showToast
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.spotify.resolveBatchForPlayback
import com.mardous.booming.online.spotify.resolveForPlayback
import com.mardous.booming.ui.IScrollHelper
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel

class SpotifyPlaylistFragment : AbsMainActivityFragment(R.layout.fragment_detail_list), IScrollHelper {

    private var _binding: FragmentDetailListBinding? = null
    private val binding get() = _binding!!

    private val playlistId: String?
        get() = arguments?.getString(ARG_PLAYLIST_ID)

    private val viewModel: SpotifyPlaylistViewModel by viewModel()

    private lateinit var trackAdapter: SpotifyTrackAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDetailListBinding.bind(view)

        setSupportActionBar(binding.toolbar, null)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        checkForMargins(binding.recyclerView)

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
                        setSupportActionBar(binding.toolbar, playlist.name)
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
                        setSupportActionBar(binding.toolbar, getString(R.string.liked_songs_label))
                    }
                }
            }
            launch {
                viewModel.tracks.collect { tracks ->
                    trackAdapter.dataSet = tracks
                    if (viewModel.isLiked.value) {
                        binding.subtitle.text =
                            getString(R.string.liked_songs_subtitle, tracks.size)
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

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = Unit

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

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
