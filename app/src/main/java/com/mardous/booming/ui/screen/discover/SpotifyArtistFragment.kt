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
import com.mardous.booming.online.data.spotify.SpotifyArtist
import com.mardous.booming.online.download.SongDownloader
import com.mardous.booming.online.spotify.resolveBatchForPlayback
import com.mardous.booming.online.spotify.resolveForPlayback
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class SpotifyArtistFragment : AbsMainActivityFragment(R.layout.fragment_detail_list) {

    private var _binding: FragmentDetailListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SpotifyArtistViewModel by viewModel()
    private val songDownloader: SongDownloader by inject()

    private val artist: SpotifyArtist?
        get() = arguments?.let {
            val id = it.getString(ARG_ARTIST_ID).orEmpty()
            if (id.isBlank()) null else SpotifyArtist(
                id = id,
                name = it.getString(ARG_NAME).orEmpty(),
                imageUrl = it.getString(ARG_IMAGE_URL)
            )
        }

    private lateinit var trackAdapter: SpotifyTrackAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDetailListBinding.bind(view)

        val artist = artist
        val name = artist?.name.orEmpty()
        setSupportActionBar(binding.toolbar, name)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.title.text = name
        binding.subtitle.text = getString(R.string.spotify_artist_subtitle)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        checkForMargins(binding.recyclerView)
        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace())
        }

        trackAdapter = SpotifyTrackAdapter(
            onTrackClick = { index -> playTrackAt(index) },
            onPlayNext = { index -> playNextAt(index) },
            onAddToQueue = { index -> enqueueAt(index) },
            onDownload = { index -> downloadAt(index) }
        )
        binding.recyclerView.adapter = trackAdapter

        binding.playAction.setOnClickListener { playAll(shuffle = false) }
        binding.shuffleAction.setOnClickListener { playAll(shuffle = true) }

        artist?.let { viewModel.load(it.id) }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                viewModel.tracks.collect { tracks ->
                    trackAdapter.dataSet = tracks
                    trackAdapter.downloadedTrackIds = songDownloader.downloadedSpotifyTrackIds()
                }
            }
            launch {
                viewModel.loading.collect { loading ->
                    binding.progressIndicator.isVisible = loading
                    binding.recyclerView.isVisible = !loading && trackAdapter.itemCount > 0
                    binding.empty.isVisible = !loading && trackAdapter.itemCount == 0
                }
            }
            launch {
                songDownloader.events.collect {
                    trackAdapter.downloadedTrackIds = songDownloader.downloadedSpotifyTrackIds()
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
                val rest = viewModel.tracks.value.drop(index + 1)
                playerViewModel.addOnlineQueue(rest.resolveBatchForPlayback(20))
            }
        }
    }

    private fun playNextAt(index: Int) {
        val track = viewModel.tracks.value.getOrNull(index) ?: return
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

    private fun enqueueAt(index: Int) {
        val track = viewModel.tracks.value.getOrNull(index) ?: return
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

    private fun downloadAt(index: Int) {
        val track = viewModel.tracks.value.getOrNull(index) ?: return
        songDownloader.enqueue(listOf(track))
        showToast(getString(R.string.download_queued_toast, 1))
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
                playerViewModel.addOnlineQueue(ordered.drop(1).resolveBatchForPlayback(20))
            }
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = Unit

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_ARTIST_ID = "artistId"
        const val ARG_NAME = "artistName"
        const val ARG_IMAGE_URL = "artistImageUrl"

        fun args(artist: SpotifyArtist) = Bundle().apply {
            putString(ARG_ARTIST_ID, artist.id)
            putString(ARG_NAME, artist.name)
            putString(ARG_IMAGE_URL, artist.imageUrl)
        }
    }
}
