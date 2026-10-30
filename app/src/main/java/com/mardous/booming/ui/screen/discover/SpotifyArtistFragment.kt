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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.mardous.booming.R
import com.mardous.booming.core.sort.SongSortMode
import com.mardous.booming.databinding.FragmentDetailListBinding
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.extensions.collectPlayerAccent
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.resources.applyPlayerAccent
import com.mardous.booming.extensions.setSupportActionBar
import com.mardous.booming.extensions.showToast
import com.mardous.booming.online.data.spotify.SpotifyArtist
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.download.DownloadRegistry
import com.mardous.booming.online.download.SongDownloader
import com.mardous.booming.online.spotify.resolveBatchForPlayback
import com.mardous.booming.online.spotify.resolveForPlayback
import com.mardous.booming.ui.ISongCallback
import com.mardous.booming.ui.adapters.SectionHeaderAdapter
import com.mardous.booming.ui.adapters.song.SimpleSongAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.component.menu.onSongMenu
import com.mardous.booming.ui.component.menu.onSongsMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Unified Spotify artist page: the artist's LOCAL library songs (matched
 * by name) above their Spotify top tracks, in one list. Either section
 * hides when it has nothing to show.
 */
class SpotifyArtistFragment : AbsMainActivityFragment(R.layout.fragment_detail_list), ISongCallback {

    private var _binding: FragmentDetailListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SpotifyArtistViewModel by viewModel()
    private val songDownloader: SongDownloader by inject()
    private val repository: Repository by inject()

    private val artist: SpotifyArtist?
        get() = arguments?.let {
            val id = it.getString(ARG_ARTIST_ID).orEmpty()
            if (id.isBlank()) null else SpotifyArtist(
                id = id,
                name = it.getString(ARG_NAME).orEmpty(),
                imageUrl = it.getString(ARG_IMAGE_URL)
            )
        }

    private lateinit var localHeaderAdapter: SectionHeaderAdapter
    private lateinit var localSongAdapter: SimpleSongAdapter
    private lateinit var spotifyHeaderAdapter: SectionHeaderAdapter
    private lateinit var trackAdapter: SpotifyTrackAdapter

    private var localSongs: List<Song> = emptyList()
    private var remoteTracks: List<SpotifyTrack> = emptyList()

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

        localHeaderAdapter = SectionHeaderAdapter(getString(R.string.songs_label))
        localSongAdapter = SimpleSongAdapter(
            context = requireActivity(),
            songs = emptyList(),
            layoutRes = R.layout.item_list,
            sortMode = SongSortMode.ArtistSongs,
            callback = this
        )
        spotifyHeaderAdapter = SectionHeaderAdapter(getString(R.string.spotify_section_title))
        trackAdapter = SpotifyTrackAdapter(
            requireActivity(),
            onTrackClick = { index -> playTrackAt(index) },
            onPlayNext = { index -> playNextAt(index) },
            onAddToQueue = { index -> enqueueAt(index) },
            onDownload = { index -> downloadAt(index) },
            onMultipleItemsAction = { menuItem, songs ->
                handleOnlineSongsMenu(menuItem, songs)
            }
        )
        binding.recyclerView.adapter = ConcatAdapter(
            ConcatAdapter.Config.Builder()
                .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
                .build(),
            localHeaderAdapter, localSongAdapter, spotifyHeaderAdapter, trackAdapter
        )

        binding.playAction.setOnClickListener { playAll(shuffle = false) }
        binding.shuffleAction.setOnClickListener { playAll(shuffle = true) }
        collectPlayerAccent { accent ->
            binding.playAction.applyPlayerAccent(accent)
            binding.shuffleAction.applyPlayerAccent(accent, tonal = true)
            binding.progressIndicator.applyPlayerAccent(accent)
            // Badge tints are applied at bind time - rebind so they follow.
            trackAdapter.notifyDataSetChanged()
        }

        // Unified detail: match the artist against the local library by
        // name; their songs render above the Spotify top tracks.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val matched = runCatching { repository.albumArtistByName(name) }.getOrNull()
            val songs = matched?.sortedSongs.orEmpty()
            withContext(Dispatchers.Main) {
                if (songs != localSongs) {
                    localSongs = songs
                    localSongAdapter.dataSet = songs
                }
                localHeaderAdapter.setVisible(songs.isNotEmpty())
                applyTracks()
            }
        }

        artist?.let { viewModel.load(it.id) }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                viewModel.tracks.collect { tracks ->
                    trackAdapter.downloadedTrackIds = songDownloader.downloadedSpotifyTrackIds()
                    if (tracks != remoteTracks) {
                        remoteTracks = tracks
                        applyTracks()
                    }
                }
            }
            launch {
                viewModel.loading.collect { loading ->
                    applyTracks()
                }
            }
            launch {
                songDownloader.events.collect {
                    trackAdapter.downloadedTrackIds = songDownloader.downloadedSpotifyTrackIds()
                }
            }
            launch {
                DownloadRegistry.downloadedSpotifyIds.collect { ids ->
                    trackAdapter.downloadedTrackIds = ids
                }
            }
        }
    }

    private fun applyTracks() {
        val tracks = remoteTracks
        val loading = viewModel.loading.value
        spotifyHeaderAdapter.setVisible(tracks.isNotEmpty())
        if (trackAdapter.dataSet != tracks) {
            trackAdapter.dataSet = tracks
        }
        val total = localSongs.size + tracks.size
        binding.progressIndicator.isVisible = loading && total == 0
        binding.recyclerView.isVisible = !binding.progressIndicator.isVisible && total > 0
        binding.empty.isVisible = !binding.progressIndicator.isVisible && total == 0
    }

    private fun handleOnlineSongsMenu(menuItem: android.view.MenuItem, songs: List<Song>) {
        val tracks = songs.mapNotNull { song ->
            viewModel.tracks.value.firstOrNull { it.id == song.data.removePrefix("yt:") }
        }
        when (menuItem.itemId) {
            R.id.action_play_next,
            R.id.action_add_to_playing_queue -> {
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
                val rest = tracks.drop(index + 1)
                playerViewModel.addOnlineQueue(rest.resolveBatchForPlayback(20))
            }
            if (index > 0) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    var insertIndex = 0
                    tracks.take(index).chunked(20).forEach { chunk ->
                        val items = chunk.resolveBatchForPlayback(20)
                        if (items.isNotEmpty()) {
                            playerViewModel.addOnlineQueueAt(insertIndex, items)
                            insertIndex += items.size
                        }
                    }
                }
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
        // Local songs win when the artist exists locally; otherwise
        // resolve the Spotify top tracks to streams.
        if (localSongs.isNotEmpty()) {
            if (shuffle) {
                playerViewModel.openAndShuffleQueue(localSongs)
            } else {
                playerViewModel.openQueue(localSongs, shuffleMode = com.mardous.booming.core.model.shuffle.OpenShuffleMode.Off)
            }
            return
        }
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

    override fun songMenuItemClick(
        song: Song,
        menuItem: MenuItem,
        sharedElements: Array<Pair<View, String>>?
    ): Boolean {
        return song.onSongMenu(this, menuItem)
    }
    override fun songsMenuItemClick(songs: List<Song>, menuItem: MenuItem) {
        songs.onSongsMenu(this, menuItem)
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
