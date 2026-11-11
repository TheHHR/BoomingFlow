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

package com.mardous.booming.data.repository

import android.content.Context
import com.mardous.booming.R
import com.mardous.booming.data.SearchFilter
import com.mardous.booming.data.local.search.SearchMatcher
import com.mardous.booming.data.local.search.SearchMatcher.SearchableText
import com.mardous.booming.data.mapper.toSongs
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.Genre
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.search.SearchQuery
import com.mardous.booming.util.Preferences

interface SearchRepository {
    suspend fun searchAll(context: Context, query: SearchQuery, filter: SearchFilter?): List<Any>
    suspend fun searchGenreSongs(genreId: Long, query: String): List<Song>
    suspend fun searchPlaylistSongs(playlistId: Long, query: String): List<Song>
    suspend fun searchYearSongs(year: Int, query: String): List<Song>
    suspend fun searchFolderSongs(folderPath: String, query: String): List<Song>
}

/**
 * All matching goes through [SearchMatcher] (tokenized, accent/punctuation
 * folded, relevance-ranked) over the full lists, replacing the old SQL
 * `LIKE '%query%'` pre-filters: a contiguous one-field substring was far
 * stricter than Spotify-style search ("eminem lose" never matched
 * "Lose Yourself" by Eminem).
 */
class RealSearchRepository(
    private val albumRepository: RealAlbumRepository,
    private val songRepository: RealSongRepository,
    private val artistRepository: RealArtistRepository,
    private val playlistRepository: RealPlaylistRepository,
    private val genreRepository: GenreRepository,
    private val specialRepository: SpecialRepository
) : SearchRepository {

    override suspend fun searchAll(context: Context, query: SearchQuery, filter: SearchFilter?): List<Any> {
        val results = ArrayList<Any>()
        if (!query.searched.isNullOrEmpty()) {
            if (filter != null) {
                if (query.filterMode != null) {
                    val filteredResults = filter.getResults(query.filterMode, query.searched)
                    if (filteredResults.isNotEmpty()) {
                        results.addAll(filteredResults)
                    }
                }
                // we do nothing if there is a filter but search mode is not valid
            } else {
                val isOnlyAlbumArtists = Preferences.onlyAlbumArtists
                when (query.filterMode) {
                    SearchQuery.FilterMode.Songs -> results.addAll(getSongs(query.searched))
                    SearchQuery.FilterMode.Albums -> results.addAll(getAlbums(query.searched))
                    SearchQuery.FilterMode.Artists -> results.addAll(getArtists(query.searched, isOnlyAlbumArtists))
                    SearchQuery.FilterMode.Genres -> results.addAll(getGenres(query.searched))
                    SearchQuery.FilterMode.Playlists -> results.addAll(getPlaylists(query.searched))
                    else -> {
                        results.addTitled(getSongs(query.searched), context.getString(R.string.songs_label))
                        results.addTitled(
                            getArtists(query.searched, isOnlyAlbumArtists),
                            if (isOnlyAlbumArtists)
                                context.getString(R.string.album_artists_label)
                            else context.getString(R.string.artists_label)
                        )
                        results.addTitled(getAlbums(query.searched), context.getString(R.string.albums_label))
                        results.addTitled(getGenres(query.searched), context.getString(R.string.genres_label))
                        results.addTitled(getPlaylists(query.searched), context.getString(R.string.playlists_label))
                    }
                }
            }
        }
        return results
    }

    override suspend fun searchGenreSongs(genreId: Long, query: String): List<Song> =
        SearchMatcher.filterRanked(genreRepository.songs(genreId), query, ::songText)

    override suspend fun searchPlaylistSongs(playlistId: Long, query: String): List<Song> =
        playlistRepository.searchPlaylistSongs(playlistId, query).toSongs()

    override suspend fun searchYearSongs(year: Int, query: String): List<Song> =
        SearchMatcher.filterRanked(specialRepository.songsByYear(year), query, ::songText)

    override suspend fun searchFolderSongs(folderPath: String, query: String): List<Song> =
        SearchMatcher.filterRanked(
            specialRepository.songsByFolder(folderPath, includeSubfolders = false),
            query,
            ::songText
        )

    private fun getSongs(query: String): List<Song> =
        SearchMatcher.filterRanked(songRepository.songs(), query, ::songText)

    private fun getAlbums(query: String): List<Album> =
        SearchMatcher.filterRanked(albumRepository.albums(), query) { album ->
            SearchableText(album.name, album.albumArtistName)
        }

    /**
     * Tokens match the split artist display NAME only, preserving the
     * no-collaborator-leak semantics (searching "Eminem" must not return
     * Rihanna/D12 split out of combined tags).
     */
    private fun getArtists(query: String, isOnlyAlbumArtists: Boolean): List<Artist> {
        val artists = if (isOnlyAlbumArtists) artistRepository.albumArtists() else artistRepository.artists()
        return SearchMatcher.filterRanked(artists, query) { artist -> SearchableText(artist.name) }
    }

    private suspend fun getGenres(query: String): List<Genre> =
        SearchMatcher.filterRanked(genreRepository.genres(), query) { genre -> SearchableText(genre.name) }

    private suspend fun getPlaylists(query: String) = playlistRepository.searchPlaylists(query)

    private fun songText(song: Song) = SearchableText(song.title, song.artistName, song.albumName)

    private fun MutableList<Any>.addTitled(results: List<Any>, header: String) {
        if (results.isNotEmpty()) {
            this.add(header)
            this.addAll(results)
        }
    }
}
