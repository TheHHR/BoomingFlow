package com.mardous.booming.ui.screen.discover

import android.annotation.SuppressLint
import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import com.mardous.booming.R
import com.mardous.booming.coil.DEFAULT_PLAYLIST_IMAGE
import com.mardous.booming.coil.DEFAULT_SONG_IMAGE
import com.mardous.booming.coil.placeholderDrawableRes
import com.mardous.booming.databinding.FragmentSpotifySearchBinding
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.navigation.spotifyPlaylistArgs
import com.mardous.booming.extensions.showToast
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyPlaylist
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import com.mardous.booming.online.data.spotify.SpotifySearchResults
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.data.spotify.thumbnailUrl
import com.mardous.booming.online.download.SongDownloader
import com.mardous.booming.online.spotify.resolveBatchForPlayback
import com.mardous.booming.online.spotify.resolveForPlayback
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import coil3.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

sealed interface SpotifySearchUiState {
    data object Idle : SpotifySearchUiState
    data object NotConnected : SpotifySearchUiState
    data object Loading : SpotifySearchUiState
    data class Results(val results: SpotifySearchResults) : SpotifySearchUiState
    data class Error(val message: String?) : SpotifySearchUiState
}

@OptIn(FlowPreview::class)
class SpotifySearchViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<SpotifySearchUiState>(SpotifySearchUiState.Idle)
    val uiState = _uiState.asStateFlow()

    val results: SpotifySearchResults?
        get() = (_uiState.value as? SpotifySearchUiState.Results)?.results

    private val queryFlow = MutableStateFlow("")
    private var searchJob: Job? = null
    private var currentQuery = ""
    private var trackOffset = 0
    private var loadingMore = false

    init {
        viewModelScope.launch {
            queryFlow.debounce(400).distinctUntilChanged().collect { query ->
                if (query.isNotBlank()) {
                    executeSearch(query)
                }
            }
        }
    }

    fun updateQuery(query: String) {
        queryFlow.value = query
        if (query.isBlank()) {
            searchJob?.cancel()
            _uiState.value = SpotifySearchUiState.Idle
        }
    }

    fun retry() {
        executeSearch(queryFlow.value)
    }

    fun loadMore() {
        if (loadingMore || currentQuery.isBlank()) return
        val current = results ?: return
        if (!current.canLoadMore) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val more = SpotifyClient.searchTracks(currentQuery, limit = PAGE_SIZE, offset = trackOffset + PAGE_SIZE)
                trackOffset += PAGE_SIZE
                if (more.isNotEmpty()) {
                    _uiState.value = SpotifySearchUiState.Results(
                        SpotifySearchResults(
                            tracks = current.tracks + more,
                            playlists = current.playlists,
                            canLoadMore = more.size >= PAGE_SIZE
                        )
                    )
                } else {
                    _uiState.value = SpotifySearchUiState.Results(
                        current.copy(canLoadMore = false)
                    )
                }
            } catch (_: Exception) {
                // Keep current results on failure; user can retry by scrolling again.
            } finally {
                loadingMore = false
            }
        }
    }

    private fun executeSearch(query: String) {
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!SpotifyPrefs.isConnected(getApplication())) {
                _uiState.value = SpotifySearchUiState.NotConnected
                return@launch
            }
            _uiState.value = SpotifySearchUiState.Loading
            _uiState.value = try {
                val results = SpotifyClient.search(query, limit = PAGE_SIZE)
                if (results.tracks.isEmpty() && results.playlists.isEmpty()) {
                    SpotifySearchUiState.Error(null)
                } else {
                    currentQuery = query
                    trackOffset = 0
                    SpotifySearchUiState.Results(results)
                }
            } catch (e: Exception) {
                SpotifySearchUiState.Error(e.message)
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}

class SpotifySearchFragment : AbsMainActivityFragment(R.layout.fragment_spotify_search) {

    private var _binding: FragmentSpotifySearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SpotifySearchViewModel by viewModel()
    private val songDownloader: SongDownloader by inject()

    private var playlistAdapter: SearchPlaylistAdapter? = null
    private var trackAdapter: SpotifyTrackAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSpotifySearchBinding.bind(view)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.searchView.requestFocus()
        binding.searchView.doAfterTextChanged { viewModel.updateQuery(it?.toString().orEmpty()) }
        showKeyboard()

        playlistAdapter = SearchPlaylistAdapter { playlistId ->
            findNavController().navigate(R.id.nav_spotify_playlist, spotifyPlaylistArgs(playlistId))
        }
        trackAdapter = SpotifyTrackAdapter(
            onTrackClick = { index -> playTrackAt(index) },
            onPlayNext = { index -> resolveAndPlayNext(index) },
            onAddToQueue = { index -> resolveAndEnqueue(index) },
            onDownload = { index ->
                viewModel.results?.tracks?.getOrNull(index)?.let { track ->
                    songDownloader.enqueue(listOf(track))
                    showToast(getString(R.string.download_queued_toast, 1))
                }
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val itemCount = recyclerView.adapter?.itemCount ?: return
                if (layoutManager.findLastVisibleItemPosition() >= itemCount - 6) {
                    viewModel.loadMore()
                }
            }
        })
        checkForMargins(binding.recyclerView)
        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace())
        }
        binding.recyclerView.adapter = ConcatAdapter(playlistAdapter, trackAdapter)

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch { viewModel.uiState.collect { render(it) } }
            launch {
                songDownloader.events.collect {
                    trackAdapter?.downloadedTrackIds = songDownloader.downloadedSpotifyTrackIds()
                }
            }
        }
        trackAdapter?.downloadedTrackIds = songDownloader.downloadedSpotifyTrackIds()
    }

    private fun render(state: SpotifySearchUiState) {
        binding.progress.isVisible = state is SpotifySearchUiState.Loading
        when (state) {
            SpotifySearchUiState.Idle -> {
                binding.empty.isVisible = false
                playlistAdapter?.submit(emptyList())
                trackAdapter?.dataSet = emptyList()
            }
            SpotifySearchUiState.NotConnected -> {
                binding.empty.setText(R.string.discover_connect_summary)
                binding.empty.isVisible = true
            }
            SpotifySearchUiState.Loading -> Unit
            is SpotifySearchUiState.Results -> {
                binding.empty.isVisible = false
                playlistAdapter?.submit(state.results.playlists)
                trackAdapter?.dataSet = state.results.tracks
            }
            is SpotifySearchUiState.Error -> {
                binding.empty.setText(R.string.discover_error)
                binding.empty.isVisible = true
            }
        }
    }

    private fun showKeyboard() {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.showSoftInput(binding.searchView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun playTrackAt(index: Int) {
        val track = viewModel.results?.tracks?.getOrNull(index) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { track.resolveForPlayback() }
            if (item == null) {
                showToast(getString(R.string.no_youtube_match_found, track.name))
                return@launch
            }
            playerViewModel.playOnlineQueue(listOf(item))
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val rest = viewModel.results?.tracks?.drop(index + 1) ?: emptyList()
                playerViewModel.addOnlineQueue(rest.resolveBatchForPlayback(20))
            }
        }
    }

    private fun resolveAndPlayNext(index: Int) {
        val track = viewModel.results?.tracks?.getOrNull(index) ?: return
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

    private fun resolveAndEnqueue(index: Int) {
        val track = viewModel.results?.tracks?.getOrNull(index) ?: return
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = Unit

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
}

private class SearchPlaylistAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<SearchPlaylistAdapter.Holder>() {

    private var items: List<SpotifyPlaylist> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(items: List<SpotifyPlaylist>) {
        this.items = items
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ShapeableImageView = view.findViewById(R.id.image)
        val title: MaterialTextView = view.findViewById(R.id.title)
        val subtitle: MaterialTextView = view.findViewById(R.id.subtitle)
        val menu: ImageButton = view.findViewById(R.id.menu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_spotify_search_playlist, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val playlist = items[position]
        holder.title.text = playlist.name
        holder.subtitle.text = holder.itemView.context.getString(
            R.string.discover_playlist_subtitle,
            playlist.owner?.displayName.orEmpty(),
            playlist.tracks?.total ?: 0
        )
        holder.image.load(playlist.thumbnailUrl()) {
            placeholderDrawableRes(holder.itemView.context, DEFAULT_PLAYLIST_IMAGE)
        }
        holder.itemView.setOnClickListener { onClick(playlist.id) }
        holder.menu.isVisible = false
    }
}
