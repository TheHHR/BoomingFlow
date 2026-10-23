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
import com.mardous.booming.extensions.navigation.spotifyPlaylistArgs
import com.mardous.booming.online.data.spotify.SpotifyHomeItem
import com.mardous.booming.online.viewmodel.SpotifyAuthState
import com.mardous.booming.online.viewmodel.StreamingAuthViewModel
import com.mardous.booming.ui.IScrollHelper
import com.mardous.booming.ui.adapters.HorizontalListAdapter
import com.mardous.booming.ui.component.base.AbsMainActivityFragment
import com.mardous.booming.ui.screen.settings.streaming.StreamingLoginDialogFragment
import com.mardous.booming.ui.screen.settings.streaming.StreamingLoginMode
import com.mardous.booming.ui.screen.settings.streaming.StreamingService
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class DiscoverFragment : AbsMainActivityFragment(R.layout.fragment_discover), IScrollHelper {

    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!

    private val discoverViewModel: DiscoverViewModel by viewModel()
    private val authViewModel: StreamingAuthViewModel by activityViewModel()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDiscoverBinding.bind(view)

        binding.appBarLayout.title = getString(R.string.discover_label)
        binding.appBarLayout.setExpanded(true, true)
        binding.appBarLayout.toolbar.setNavigationOnClickListener {
            findNavController().navigate(R.id.nav_spotify_search)
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
        binding.retryButton.setOnClickListener { discoverViewModel.load() }

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
                                discoverViewModel.load()
                            }
                        }
                        SpotifyAuthState.NOT_CONNECTED -> discoverViewModel.onNotConnected()
                        else -> Unit
                    }
                    previousState = state
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

                val adapters = mutableListOf<RecyclerView.Adapter<*>>()
                val greeting = state.feed.greeting
                if (!greeting.isNullOrEmpty()) {
                    adapters += GreetingAdapter(greeting)
                }
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
                binding.recyclerView.adapter = ConcatAdapter(adapters)
            }
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_discover, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_refresh) {
            discoverViewModel.load()
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
