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
import com.mardous.booming.extensions.media.splitArtistName
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
        val artists = splitIntoArtists(albumRepository.splitIntoAlbums(songs)).filter {
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
            // rebuilding the split artist from all songs.
            var resolvedName: String? = null
            val songs = songRepository.songs(
                songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
            )
            val albums = with(AlbumSortMode.ArtistAlbums) {
                albumRepository.splitIntoAlbums(songs).filter { album ->
                    val rawName = album.artistName?.collapseSpaces() ?: return@filter false
                    val match = rawName.splitArtistName().firstOrNull {
                        syntheticArtistId(it.lowercase()) == artistId
                    }
                    if (match != null && resolvedName == null) resolvedName = match
                    match != null
                }.sorted()
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
        val artists = splitIntoArtists(albumRepository.splitIntoAlbums(songs))
        return sortArtists(artists)
    }

    override fun albumArtists(): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(null, null, "lower(${AudioColumns.ALBUM_ARTIST})")
        )
        val minimumSongCount = Preferences.minimumSongCountForArtist
        val albumArtists = splitIntoAlbumArtists(albumRepository.splitIntoAlbums(songs)).filter {
            it.songCount >= minimumSongCount
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "albumArtists: ${albumArtists.size} entries from ${songs.size} songs")
        }
        return sortArtists(albumArtists)
    }

    override fun albumArtist(artistName: String): Artist {
        if (Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(artistName, ignoreCase = true)) {
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
        // match. Build the artist from all songs, keeping the albums whose
        // album-artist tag splits to this name - the same construction the
        // artists list uses, so list and detail always agree.
        val songs = songRepository.songs(
            songRepository.makeSongCursor(null, null, DEFAULT_SORT_ORDER)
        )
        val albums = with(AlbumSortMode.ArtistAlbums) {
            albumRepository.splitIntoAlbums(songs)
                .filter { album ->
                    val rawName = album.albumArtistName?.collapseSpaces() ?: return@filter false
                    rawName.equals(artistName, ignoreCase = true) ||
                            rawName.splitArtistName().any { it.equals(artistName, ignoreCase = true) }
                }
                .sorted()
        }
        return Artist(
            artistName = artistName,
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
        val artists = splitIntoAlbumArtists(albumRepository.splitIntoAlbums(songs))
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
        return splitIntoAlbumArtists(albumRepository.splitIntoAlbums(songs, sorted = false)).take(MAX_SIMILAR_ARTISTS)
    }

    private fun splitIntoArtists(albums: List<Album>): List<Artist> {
        val filterSingles = this.filterSingles
        // Multi-artist artist tags ("Eminem & King") are split using the
        // configured separators; the album is assigned to every resulting
        // artist (same behavior as the album-artists view). Split entries
        // use stable synthetic ids and a display-name override.
        val albumsByArtistName = LinkedHashMap<String, MutableList<Album>>()
        val displayNames = HashMap<String, String>()
        for (album in albums) {
            val rawName = album.artistName?.collapseSpaces() ?: continue
            if (rawName.isEmpty()) continue
            for (part in rawName.splitArtistName()) {
                if (Preferences.isIgnoredArtist(part)) continue
                val key = part.lowercase()
                albumsByArtistName.getOrPut(key) { mutableListOf() }.add(album)
                displayNames.putIfAbsent(key, part)
            }
        }
        return albumsByArtistName.map { (key, currentAlbums) ->
            val artistName = displayNames.getValue(key)
            val sortedAlbums = with(AlbumSortMode.ArtistAlbums) { currentAlbums.sorted() }
            Artist(
                id = syntheticArtistId(key),
                albums = sortedAlbums,
                filterSingles = filterSingles,
                nameOverride = artistName
            )
        }
    }

    fun splitIntoAlbumArtists(albums: List<Album>): List<Artist> {
        val filterSingles = this.filterSingles
        // Multi-artist album-artist tags ("Eminem & King") are split using
        // the configured separators; the album is assigned to every resulting
        // artist, and names on the ignore list ("Not artist") are dropped.
        val albumsByArtistName = LinkedHashMap<String, MutableList<Album>>()
        val displayNames = HashMap<String, String>()
        for (album in albums) {
            val rawName = album.albumArtistName?.collapseSpaces() ?: continue
            if (rawName.isEmpty()) continue
            for (part in rawName.splitArtistName()) {
                if (Preferences.isIgnoredArtist(part)) continue
                val key = part.lowercase()
                albumsByArtistName.getOrPut(key) { mutableListOf() }.add(album)
                displayNames.putIfAbsent(key, part)
            }
        }
        return albumsByArtistName.map { (key, currentAlbums) ->
            val artistName = displayNames.getValue(key)
            val sortedAlbums = with(AlbumSortMode.ArtistAlbums) { currentAlbums.sorted() }
            if (Artist.VARIOUS_ARTISTS_DISPLAY_NAME.equals(artistName, ignoreCase = true)) {
                Artist(Artist.VARIOUS_ARTISTS_ID, sortedAlbums, filterSingles, isAlbumArtist = true)
            } else {
                Artist(
                    id = currentAlbums[0].artistId,
                    albums = sortedAlbums,
                    filterSingles = filterSingles,
                    isAlbumArtist = true,
                    nameOverride = artistName
                )
            }
        }
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