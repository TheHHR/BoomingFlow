package com.mardous.booming.ui.screen.discover

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.R
import com.mardous.booming.databinding.FragmentDiscoverBinding
import com.mardous.booming.extensions.launchAndRepeatWithViewLifecycle
import com.mardous.booming.extensions.collectPlayerAccent
import com.mardous.booming.extensions.resources.applyPlayerAccent
import com.mardous.booming.extensions.navigation.spotifyPlaylistArgs
import com.mardous.booming.online.data.spotify.SpotifyHomeItem
import com.mardous.booming.online.data.spotify.SpotifyPlaylist
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import com.mardous.booming.online.data.spotify.thumbnailUrl
import com.mardous.booming.online.viewmodel.SpotifyAuthState
import com.mardous.booming.online.viewmodel.StreamingAuthViewModel
import com.mardous.booming.ui.IScrollHelper
import com.mardous.booming.ui.adapters.HorizontalListAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.screen.library.playlists.PlaylistsSectionsViewModel
import com.mardous.booming.ui.screen.settings.streaming.StreamingLoginDialogFragment
import com.mardous.booming.ui.screen.settings.streaming.StreamingLoginMode
import com.mardous.booming.ui.screen.settings.streaming.StreamingService
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class DiscoverFragment : AbsMainActivityFragment(R.layout.fragment_discover), IScrollHelper {

    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!

    private val discoverViewModel: DiscoverViewModel by activityViewModel()
    private val authViewModel: StreamingAuthViewModel by activityViewModel()

    /**
     * Own fragment-scoped sections instance (not shared with the Playlists
     * tab, whose per-visit reload behavior must not change): it reads and
     * revalidates the same playlist_cache/sections.json, so the Discover
     * library shelf restores instantly and works offline.
     */
    private val sectionsViewModel: PlaylistsSectionsViewModel by viewModel()

    private var spotifyPlaylists: List<SpotifyPlaylist> = emptyList()
    private var likedTotal: Int? = null
    private var spotifyConnected = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDiscoverBinding.bind(view)

        binding.appBarLayout.title = getString(R.string.discover_label)
        binding.appBarLayout.setExpanded(true, true)
        binding.appBarLayout.toolbar.setNavigationOnClickListener {
            findNavController().navigate(R.id.nav_search)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        checkForMargins(binding.recyclerView)
        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            binding.recyclerView.updatePadding(bottom = it.getWithSpace())
        }

        binding.connectButton.setOnClickListener {
            StreamingLoginDialogFragment.create(StreamingService.Spotify, StreamingLoginMode.WebView)
                .show(childFragmentManager, "spotify_connect")
        }
        binding.retryButton.setOnClickListener { discoverViewModel.load(force = true) }
        collectPlayerAccent { accent ->
            binding.retryButton.applyPlayerAccent(accent)
            binding.progress.applyPlayerAccent(accent)
        }

        viewLifecycleOwner.launchAndRepeatWithViewLifecycle {
            launch {
                discoverViewModel.uiState.collect { state -> render(state) }
            }
                launch {
                    var previousState: SpotifyAuthState? = null
                    authViewModel.authState.collect { state ->
                        when (state) {
                            SpotifyAuthState.CONNECTED -> {
                                if (previousState != SpotifyAuthState.CONNECTED) {
                                    discoverViewModel.load(force = true)
                                }
                            }
                        SpotifyAuthState.NOT_CONNECTED -> discoverViewModel.onNotConnected()
                        else -> Unit
                    }
                        // Same stored-session-counts-as-connected rule as the
                        // Playlists tab: the cached library shelf must not
                        // wait for the live verifySession() roundtrip.
                        val hasStoredSession = SpotifyPrefs.isConnected(requireContext())
                        spotifyConnected = state == SpotifyAuthState.CONNECTED ||
                            (hasStoredSession && state != SpotifyAuthState.NOT_CONNECTED)
                        // Only pull the library sections when they are
                        // actually displayed here (Playlists tab local-only).
                        if (spotifyConnected && !Preferences.showSpotifyLibraryInPlaylists) {
                            sectionsViewModel.load()
                        }
                        render(discoverViewModel.uiState.value)
                        previousState = state
                    }
            }
            launch {
                sectionsViewModel.likedTotal.collect { total ->
                    likedTotal = total
                    render(discoverViewModel.uiState.value)
                }
            }
            launch {
                sectionsViewModel.spotifyPlaylists.collect { list ->
                    spotifyPlaylists = list
                    render(discoverViewModel.uiState.value)
                }
            }
        }
    }

    private fun render(state: DiscoverUiState) {
        binding.progress.isVisible = false
        binding.connectContainer.isVisible = false
        binding.errorContainer.isVisible = false
        binding.recyclerView.isVisible = false
        binding.stateContainer.isVisible = true

        // The connect card takes over when there is no session: the
        // library shelf requires one just like the home feed.
        if (state == DiscoverUiState.NotConnected) {
            binding.connectContainer.isVisible = true
            return
        }

        // The Spotify library lives on Discover ("Show Spotify library in
        // Playlists tab" off): its shelf renders from the cache while the
        // home feed loads, and feed errors never blank the library out.
        val libraryShelf = libraryShelfAdapter()
        if (libraryShelf != null) {
            binding.stateContainer.isVisible = false
            binding.recyclerView.isVisible = true
            val adapters = mutableListOf<RecyclerView.Adapter<*>>(libraryShelf)
            if (state is DiscoverUiState.Success) {
                adapters += feedShelfAdapters(state)
            }
            binding.recyclerView.adapter = ConcatAdapter(adapters)
            return
        }

        when (state) {
            DiscoverUiState.Loading -> {
                binding.progress.isVisible = true
            }

            DiscoverUiState.NotConnected -> {
                binding.connectContainer.isVisible = true
            }

            DiscoverUiState.Empty -> {
                binding.errorText.setText(R.string.discover_empty_home)
                binding.errorContainer.isVisible = true
            }

            is DiscoverUiState.Error -> {
                binding.errorText.setText(R.string.discover_error)
                binding.errorContainer.isVisible = true
            }

            is DiscoverUiState.Success -> {
                binding.stateContainer.isVisible = false
                binding.recyclerView.isVisible = true
                binding.recyclerView.adapter = ConcatAdapter(feedShelfAdapters(state))
            }
        }
    }

    /**
     * "Your Spotify library" shelf (Liked Songs + your playlists), shown
     * only while the Playlists tab is local-only so the library stays
     * reachable from Discover. Null when the switch is on, no session, or
     * nothing cached/loaded yet.
     */
    private fun libraryShelfAdapter(): HorizontalListAdapter? {
        if (Preferences.showSpotifyLibraryInPlaylists) return null
        if (!spotifyConnected) return null
        if (likedTotal == null && spotifyPlaylists.isEmpty()) return null

        val cards = mutableListOf<SpotifyHomeItem.Playlist>()
        likedTotal?.let { total ->
            cards += SpotifyHomeItem.Playlist(
                uri = "spotify:playlist:liked",
                id = "liked",
                name = getString(R.string.liked_songs_label),
                description = null,
                imageUrl = null,
                ownerName = getString(R.string.liked_songs_subtitle, total)
            )
        }
        cards += spotifyPlaylists.map { playlist ->
            SpotifyHomeItem.Playlist(
                uri = playlist.uri ?: "spotify:playlist:${playlist.id}",
                id = playlist.id,
                name = playlist.name,
                description = playlist.description,
                imageUrl = playlist.thumbnailUrl(),
                ownerName = playlist.owner?.displayName
            )
        }

        val inner = SpotifyCardAdapter { playlistId ->
            findNavController().navigate(
                R.id.nav_spotify_playlist,
                spotifyPlaylistArgs(playlistId)
            )
        }
        inner.dataSet = cards
        return HorizontalListAdapter(getString(R.string.spotify_library_shelf_title), inner)
            .also { it.setVisible(true) }
    }

    private fun feedShelfAdapters(state: DiscoverUiState.Success): List<RecyclerView.Adapter<*>> {
        val adapters = mutableListOf<RecyclerView.Adapter<*>>()
        for (section in state.feed.sections) {
            val playlists = section.items.filterIsInstance<SpotifyHomeItem.Playlist>()
            if (playlists.isEmpty()) continue
            val inner = SpotifyCardAdapter { playlistId ->
                findNavController().navigate(
                    R.id.nav_spotify_playlist,
                    spotifyPlaylistArgs(playlistId)
                )
            }
            inner.dataSet = playlists
            val shelf = HorizontalListAdapter(section.title.orEmpty(), inner)
            shelf.setVisible(true)
            adapters += shelf
        }
        return adapters
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_discover, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_refresh) {
            discoverViewModel.load(force = true)
            return true
        }
        return false
    }

    override fun scrollToTop() {
        binding.recyclerView.scrollToPosition(0)
        binding.appBarLayout.setExpanded(true, true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
