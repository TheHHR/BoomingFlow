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

import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Audio.AudioColumns
import android.util.Log
import androidx.annotation.RequiresApi
import com.mardous.booming.BuildConfig
import com.mardous.booming.core.sort.AlbumSortMode
import com.mardous.booming.core.sort.ArtistSortMode
import com.mardous.booming.data.local.MediaQueryDispatcher
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.resolveArtistAlias
import com.mardous.booming.extensions.media.splitArtistName
import com.mardous.booming.extensions.media.splitCanonicalArtistNames
import com.mardous.booming.extensions.utilities.collapseSpaces
import com.mardous.booming.util.Preferences

interface ArtistRepository {
    fun artists(): List<Artist>
    fun artists(query: String): List<Artist>
    fun artist(artistId: Long): Artist
    fun albumArtists(): List<Artist>
    fun albumArtist(artistName: String): Artist
    fun albumArtists(query: String): List<Artist>
    fun similarAlbumArtists(artist: Artist): List<Artist>
}

class RealArtistRepository(
    private val songRepository: RealSongRepository,
    private val albumRepository: RealAlbumRepository
) : ArtistRepository {

    private val filterSingles: Boolean
        get() = Preferences.ignoreSingles

    override fun artists(): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
        )
        val minimumSongCount = Preferences.minimumSongCountForArtist
        val artists = splitIntoArtists(songs).filter {
            it.songCount >= minimumSongCount
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "artists (plain mode): ${artists.size} entries from ${songs.size} songs")
        }
        return sortArtists(artists)
    }

    override fun artist(artistId: Long): Artist {
        if (artistId == Artist.VARIOUS_ARTISTS_ID) {
            // Get Various Artists
            val songs = songRepository.songs(
                songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
            )
            val albums = with(AlbumSortMode.ArtistAlbums) {
                albumRepository.splitIntoAlbums(songs)
                    .filter { Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(it.albumArtistName, ignoreCase = true) }
                    .sorted()
            }
            return Artist(Artist.VARIOUS_ARTISTS_ID, albums, filterSingles)
        }

        if (artistId < 0) {
            // Synthetic split artist from the plain artists view: resolve by
            // rebuilding the split artist from all songs, song-level, so the
            // detail page matches the list exactly.
            var resolvedName: String? = null
            val songs = songRepository.songs(
                songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
            )
            val artistSongs = songs.filter { song ->
                val rawName = song.artistName.collapseSpaces()
                val match = rawName.splitCanonicalArtistNames().firstOrNull {
                    syntheticArtistId(it.lowercase()) == artistId
                }
                if (match != null && resolvedName == null) resolvedName = match
                match != null
            }
            val albums = with(AlbumSortMode.ArtistAlbums) {
                albumRepository.splitIntoAlbums(artistSongs).sorted()
            }
            return Artist(
                id = artistId,
                albums = albums,
                filterSingles = filterSingles,
                nameOverride = resolvedName
            )
        }

        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                AudioColumns.ARTIST_ID + "=?",
                arrayOf(artistId.toString()),
                DEFAULT_SORT_ORDER
            )
        )
        // A player's "open artist" lands here with the MediaStore id,
        // which knows nothing about merges. When any of these songs
        // carries an alias spelling, rebuild through the canonical
        // entry so the page matches the artists list exactly.
        val aliased = songs.any { song ->
            song.artistName.splitArtistName().any { part ->
                !part.equals(part.resolveArtistAlias(), ignoreCase = true)
            }
        }
        if (aliased) {
            val dominant = songs
                .flatMap { it.artistName.splitCanonicalArtistNames() }
                .groupingBy { it.lowercase() }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?.let { lower -> songs.flatMap { it.artistName.splitCanonicalArtistNames() }.firstOrNull { it.lowercase() == lower } }
                ?: return Artist(
                    id = artistId,
                    albums = albumRepository.splitIntoAlbums(
                        songs = songs,
                        sortMode = AlbumSortMode.ArtistAlbums
                    ),
                    filterSingles = filterSingles
                )
            return albumArtist(dominant)
        }
        return Artist(
            id = artistId,
            albums = albumRepository.splitIntoAlbums(
                songs = songs,
                sortMode = AlbumSortMode.ArtistAlbums
            ),
            filterSingles = filterSingles
        )
    }

    override fun artists(query: String): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(AudioColumns.ARTIST + " LIKE ?", arrayOf("%$query%"), DEFAULT_SORT_ORDER)
        )
        // The SQL pre-filter matches songs whose artist tag CONTAINS the
        // query; splitting creates entries for every part of those names,
        // so collaborators would leak into the results without this
        // display-name check.
        val trimmedQuery = query.trim()
        val artists = splitIntoArtists(songs).filter {
            it.name.contains(trimmedQuery, ignoreCase = true)
        }
        return sortArtists(artists)
    }

    override fun albumArtists(): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(null, null, "lower(${AudioColumns.ALBUM_ARTIST})")
        )
        val minimumSongCount = Preferences.minimumSongCountForArtist
        val albumArtists = splitIntoAlbumArtists(songs).filter {
            it.songCount >= minimumSongCount
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "albumArtists: ${albumArtists.size} entries from ${songs.size} songs")
        }
        return sortArtists(albumArtists)
    }

    override fun albumArtist(artistName: String): Artist {
        // Aliases resolve to the canonical entry up front, so opening a
        // merged artist by any of its spellings rebuilds from the same
        // merged song set every time: no cached entry can go stale.
        val canonicalName = artistName.resolveArtistAlias()
        if (Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(canonicalName, ignoreCase = true)) {
            // Get Various Artists
            val songs = songRepository.songs(
                songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
            )
            val albums = with(AlbumSortMode.ArtistAlbums) {
                albumRepository.splitIntoAlbums(songs)
                    .filter { Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(it.albumArtistName, ignoreCase = true) }
                    .sorted()
            }
            return Artist(Artist.VARIOUS_ARTISTS_ID, albums, filterSingles, isAlbumArtist = true)
        }

        // Album-artist tags may contain separators ("Eminem & King"), so a
        // split name ("Eminem") can never be resolved with an exact SQL
        // match. Build the artist from all songs with the same song-level
        // assignment the artists list uses - keeping only the songs that
        // actually name this artist - so list and detail always agree.
        val songs = songRepository.songs(
            songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
        )
        val fallbackByAlbum = albumFallbackNames(songs)
        val artistSongs = songs.filter { song ->
            val rawName = song.albumArtistName?.collapseSpaces()?.takeIf { it.isNotEmpty() }
                ?: fallbackByAlbum[song.albumId]
            rawName != null && rawName.splitCanonicalArtistNames().any { it.equals(canonicalName, ignoreCase = true) }
        }
        val albums = with(AlbumSortMode.ArtistAlbums) {
            albumRepository.splitIntoAlbums(artistSongs).sorted()
        }
        return Artist(
            artistName = canonicalName,
            albums = albums,
            filterSingles = filterSingles
        )
    }

    override fun albumArtists(query: String): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                "${AudioColumns.ALBUM_ARTIST} LIKE ?",
                arrayOf("%$query%"),
                DEFAULT_SORT_ORDER
            )
        )
        // Same reason as artists(query): drop split entries whose display
        // name doesn't actually match the query.
        val trimmedQuery = query.trim()
        val artists = splitIntoAlbumArtists(songs).filter {
            it.name.contains(trimmedQuery, ignoreCase = true)
        }
        return sortArtists(artists)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun similarAlbumArtists(artist: Artist): List<Artist> {
        val genreNames = artist.songs.mapNotNull { it.genreName }.distinct()
        if (genreNames.isEmpty()) {
            return arrayListOf()
        }
        val selectionBuilder = StringBuilder("${AudioColumns.GENRE} IN(?")
        for (i in 1 until genreNames.size) {
            selectionBuilder.append(",?")
        }
        selectionBuilder.append(")")
        val songs = songRepository.makeSongCursor(
            MediaQueryDispatcher()
                .setProjection(RealSongRepository.getBaseProjection())
                .setSelection(selectionBuilder.toString())
                .setSelectionArguments(genreNames.toTypedArray())
                .addSelection("(${AudioColumns.ALBUM_ARTIST} NOT NULL AND ${AudioColumns.ALBUM_ARTIST} != ?)")
                .addArguments(artist.name)
        ).let {
            songRepository.songs(it)
        }
        return splitIntoAlbumArtists(songs).take(MAX_SIMILAR_ARTISTS)
    }

    /**
     * Per-song assignment: each song belongs to every artist named in its
     * own artist tag; ignored names are dropped; the artist's albums are
     * rebuilt from its assigned songs, so song counts and album lists only
     * contain songs that actually belong to the artist.
     */
    private fun splitIntoArtists(songs: List<Song>): List<Artist> {
        val filterSingles = this.filterSingles
        val songsByArtistName = LinkedHashMap<String, MutableList<Song>>()
        val displayNames = HashMap<String, String>()
        for (song in songs) {
            val rawName = song.artistName.collapseSpaces()
            if (rawName.isEmpty()) continue
            for (part in rawName.splitCanonicalArtistNames()) {
                if (Preferences.isIgnoredArtist(part)) continue
                val key = part.lowercase()
                songsByArtistName.getOrPut(key) { mutableListOf() }.add(song)
                displayNames.putIfAbsent(key, part)
            }
        }
        return songsByArtistName.map { (key, artistSongs) ->
            val artistName = displayNames.getValue(key)
            val artistAlbums = with(AlbumSortMode.ArtistAlbums) {
                albumRepository.splitIntoAlbums(artistSongs).sorted()
            }
            Artist(
                id = syntheticArtistId(key),
                albums = artistAlbums,
                filterSingles = filterSingles,
                nameOverride = artistName
            )
        }
    }

    /**
     * Per-song assignment for the album-artists view: each song goes to
     * every artist named in its own album-artist tag, falling back to the
     * album's derived tag when the song has none (so untagged songs don't
     * vanish from the artists view).
     */
    fun splitIntoAlbumArtists(songs: List<Song>): List<Artist> {
        val filterSingles = this.filterSingles
        val fallbackByAlbum = albumFallbackNames(songs)
        val songsByArtistName = LinkedHashMap<String, MutableList<Song>>()
        val displayNames = HashMap<String, String>()
        for (song in songs) {
            val rawName = song.albumArtistName?.collapseSpaces()?.takeIf { it.isNotEmpty() }
                ?: fallbackByAlbum[song.albumId]
                ?: continue
            for (part in rawName.splitCanonicalArtistNames()) {
                if (Preferences.isIgnoredArtist(part)) continue
                val key = part.lowercase()
                songsByArtistName.getOrPut(key) { mutableListOf() }.add(song)
                displayNames.putIfAbsent(key, part)
            }
        }
        return songsByArtistName.map { (key, artistSongs) ->
            val artistName = displayNames.getValue(key)
            val artistAlbums = with(AlbumSortMode.ArtistAlbums) {
                albumRepository.splitIntoAlbums(artistSongs).sorted()
            }
            if (Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(artistName, ignoreCase = true)) {
                Artist(Artist.VARIOUS_ARTISTS_ID, artistAlbums, filterSingles, isAlbumArtist = true)
            } else {
                Artist(artistName = artistName, albums = artistAlbums, filterSingles = filterSingles)
            }
        }
    }

    /**
     * Album-based entry point for callers that only have albums (home
     * sections); flattens to songs and applies the song-level split.
     */
    @JvmName("splitIntoAlbumArtistsFromAlbums")
    fun splitIntoAlbumArtists(albums: List<Album>): List<Artist> =
        splitIntoAlbumArtists(albums.flatMap { it.songs })

    private fun albumFallbackNames(songs: List<Song>): Map<Long, String> {
        val fallback = HashMap<Long, String>()
        for (album in albumRepository.splitIntoAlbums(songs, sorted = false)) {
            val derived = album.albumArtistName?.collapseSpaces()
            if (!derived.isNullOrEmpty()) fallback[album.id] = derived
        }
        return fallback
    }

    /**
     * Stable negative id for a split artist entry, derived from the
     * normalized name (String.hashCode is guaranteed stable). Avoids the
     * -1 (empty) and -2 (Various Artists) sentinels.
     */
    private fun syntheticArtistId(lowerCasedName: String): Long {
        val hash = lowerCasedName.hashCode().toLong() and 0x7FFFFFFFL
        return -(hash + 3L)
    }

    private fun sortArtists(artists: List<Artist>): List<Artist> {
        return with(ArtistSortMode.AllArtists) { artists.sorted() }
    }

    companion object {
        private const val TAG = "ArtistRepository"
        private const val MAX_SIMILAR_ARTISTS = 10
        const val DEFAULT_SORT_ORDER =
            MediaStore.Audio.Artists.ARTIST + ", " + MediaStore.Audio.Albums.ALBUM + ", " + MediaStore.Audio.Media.DEFAULT_SORT_ORDER
    }
}