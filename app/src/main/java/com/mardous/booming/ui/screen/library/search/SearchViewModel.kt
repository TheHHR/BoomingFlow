package com.mardous.booming.ui.screen.library.search

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.mardous.booming.core.model.action.SongClickBehavior
import com.mardous.booming.data.SearchFilter
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.search.SearchQuery
import com.mardous.booming.data.repository.Repository
import com.mardous.booming.extensions.media.indexOfSong
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifySearchResults
import com.mardous.booming.online.spotify.SpotifyPlaylistCache
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SearchViewModel(
    application: Application,
    private val repository: Repository
) : AndroidViewModel(application) {

    /** Cache key of a remote playlist to search within (scoped mode). */
    var scopePlaylistKey: String? = null
        private set

    val isScoped: Boolean get() = scopePlaylistKey != null

    fun setScope(playlistKey: String?, playlistName: String?) {
        scopePlaylistKey = playlistKey
        scopePlaylistName = playlistName
    }

    var scopePlaylistName: String? = null
        private set

    private val searchQuery = MutableStateFlow(SearchQuery())

    private val _searchFilter = MutableStateFlow<SearchFilter?>(null)
    val searchFilter = _searchFilter.asStateFlow()

    private val _searchResult = MutableStateFlow<List<Any>>(emptyList())
    val searchResult = _searchResult.asStateFlow()

    private val _spotifyResult = MutableStateFlow<SpotifySearchResults?>(null)
    val spotifyResult = _spotifyResult.asStateFlow()

    private val _queueFlow = MutableSharedFlow<Triple<List<Song>, Int, SongClickBehavior>>()
    val queueFlow = _queueFlow.asSharedFlow()

    private var spotifyJob: Job? = null
    private var spotifyOffset = 0
    private var loadingMoreSpotify = false

    init {
        @OptIn(FlowPreview::class)
        combine(searchQuery, searchFilter) { query, filter -> query to filter }
            .debounce(300)
            .distinctUntilChanged()
            .onEach { (query, filter) ->
                if (isScoped) {
                    // Scoped mode: filter the remote playlist's cached track
                    // list client-side; no library or web search involved.
                    val text = query.searched.orEmpty()
                    val scope = scopePlaylistKey
                    _searchResult.value = if (text.isBlank() || scope == null) {
                        emptyList()
                    } else {
                        SpotifyPlaylistCache.loadTracks(getApplication(), scope)
                            .filter { track ->
                                track.name.contains(text, true) ||
                                    track.artists.any { it.name.contains(text, true) }
                            }
                    }
                    _spotifyResult.value = null
                    return@onEach
                }
                val result = repository.search(query, filter)
                _searchResult.value = result
                // Category-restricted searches (opened from a detail screen)
                // are library-only: no Spotify web results.
                if (filter != null) {
                    _spotifyResult.value = null
                } else {
                    executeSpotifySearch(query)
                }
            }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    private fun executeSpotifySearch(query: SearchQuery) {
        val text = query.searched.orEmpty()
        if (text.isBlank()) {
            _spotifyResult.value = null
            return
        }
        spotifyJob?.cancel()
        spotifyOffset = 0
        spotifyJob = viewModelScope.launch(Dispatchers.IO) {
            val mode = query.filterMode
            val results = try {
                val tracks = if (mode == null || mode == SearchQuery.FilterMode.Songs) {
                    SpotifyClient.searchTracks(text, limit = SPOTIFY_PAGE)
                } else emptyList()
                val playlists = if (mode == null || mode == SearchQuery.FilterMode.Playlists) {
                    SpotifyClient.searchPlaylists(text, limit = SPOTIFY_PAGE)
                } else emptyList()
                val artists = if (mode == null || mode == SearchQuery.FilterMode.Artists) {
                    SpotifyClient.searchArtists(text, limit = SPOTIFY_PAGE)
                } else emptyList()
                val albums = if (mode == null || mode == SearchQuery.FilterMode.Albums) {
                    SpotifyClient.searchAlbums(text, limit = SPOTIFY_PAGE)
                } else emptyList()
                val canLoadMore = when (mode) {
                    // The API caps album pages at ~10 regardless of the limit,
                    // so any returned albums mean more are available.
                    SearchQuery.FilterMode.Albums -> albums.isNotEmpty()
                    SearchQuery.FilterMode.Playlists -> playlists.size >= SPOTIFY_PAGE
                    SearchQuery.FilterMode.Artists -> artists.size >= SPOTIFY_PAGE
                    else -> tracks.size >= SPOTIFY_PAGE
                }
                SpotifySearchResults(
                    tracks = tracks,
                    playlists = playlists,
                    artists = artists,
                    albums = albums,
                    canLoadMore = canLoadMore
                )
            } catch (e: Exception) {
                null
            }
            _spotifyResult.value = results
        }
    }

    fun loadMoreSpotify() {
        if (isScoped) return
        if (loadingMoreSpotify) return
        val current = _spotifyResult.value ?: return
        if (!current.canLoadMore) return
        val query = searchQuery.value
        val mode = query.filterMode
        val text = query.searched ?: return
        if (text.isBlank()) return
        loadingMoreSpotify = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pageSize = SPOTIFY_PAGE
                val offset = spotifyOffset + pageSize
                when (mode) {
                    SearchQuery.FilterMode.Playlists -> {
                        val more = SpotifyClient.searchPlaylists(text, limit = pageSize, offset = offset)
                        spotifyOffset = offset
                        updateOrFinish(current, more, pageSize) { c, items, canMore ->
                            c.copy(playlists = c.playlists + items, canLoadMore = canMore)
                        }
                    }
                    SearchQuery.FilterMode.Artists -> {
                        val more = SpotifyClient.searchArtists(text, limit = pageSize, offset = offset)
                        spotifyOffset = offset
                        updateOrFinish(current, more, pageSize) { c, items, canMore ->
                            c.copy(artists = c.artists + items, canLoadMore = canMore)
                        }
                    }
                    SearchQuery.FilterMode.Albums -> {
                        val more = SpotifyClient.searchAlbums(text, limit = pageSize, offset = offset)
                        spotifyOffset = offset
                        _spotifyResult.value = if (more.isNotEmpty()) {
                            current.copy(albums = current.albums + more, canLoadMore = true)
                        } else {
                            current.copy(canLoadMore = false)
                        }
                    }
                    else -> {
                        val more = SpotifyClient.searchTracks(text, limit = pageSize, offset = offset)
                        spotifyOffset = offset
                        updateOrFinish(current, more, pageSize) { c, items, canMore ->
                            c.copy(tracks = c.tracks + items, canLoadMore = canMore)
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep current results; scrolling again retries.
            } finally {
                loadingMoreSpotify = false
            }
        }
    }

    private fun <T> updateOrFinish(
        current: SpotifySearchResults,
        more: List<T>,
        pageSize: Int,
        merge: (SpotifySearchResults, List<T>, Boolean) -> SpotifySearchResults
    ) {
        _spotifyResult.value = if (more.isNotEmpty()) {
            merge(current, more, more.size >= pageSize)
        } else {
            current.copy(canLoadMore = false)
        }
    }

    fun updateFilter(filter: SearchFilter?) {
        _searchFilter.value = filter
    }

    fun updateQuery(
        mode: SearchQuery.FilterMode? = searchQuery.value.filterMode,
        query: String? = searchQuery.value.searched
    ) {
        searchQuery.value = searchQuery.value.copy(filterMode = mode, searched = query)
    }

    fun songClick(song: Song, results: List<Any>) = viewModelScope.launch(Dispatchers.IO) {
        val songs = if (Preferences.playAllSongsWhenSearching) {
            repository.allSongs()
        } else {
            results.filterIsInstance<Song>()
        }
        val startPos = if (songs.size == 1) {
            if (songs.singleOrNull { it.id == song.id } != null) 0 else -1
        } else {
            songs.indexOfSong(song.id).coerceAtLeast(0)
        }
        if (startPos != -1) {
            _queueFlow.emit(Triple(songs, startPos, Preferences.songClickAction))
        }
    }

    fun refresh() {
        searchQuery.value = searchQuery.value.copy(timestamp = System.currentTimeMillis())
    }

    companion object {
        private const val SPOTIFY_PAGE = 20
    }
}