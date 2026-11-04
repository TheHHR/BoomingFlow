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
package com.mardous.booming.core.playlist

import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.media.resolveArtistAlias
import com.mardous.booming.extensions.media.splitCanonicalArtistNames
import com.mardous.booming.extensions.utilities.collapseSpaces

/**
 * Reference sets used to validate synced-playlist rule input. Every set
 * is built with the exact same canonicalization the rule matcher uses,
 * so anything that passes validation will also match at sync time.
 */
data class SyncRuleValidationData(
    val folderSegments: Set<String>,
    val artistNames: Set<String>,
    val genreNames: Set<String>,
    val playlistNames: Set<String>
) {
    companion object {
        fun fromSongs(songs: List<Song>, playlistNames: Collection<String>): SyncRuleValidationData {
            val folders = HashSet<String>()
            val artists = HashSet<String>()
            val genres = HashSet<String>()
            for (song in songs) {
                song.data.split('/')
                    .filter { it.isNotEmpty() }
                    .forEach { folders.add(it.lowercase()) }
                val albumArtist = song.albumArtistName?.collapseSpaces()
                if (!albumArtist.isNullOrEmpty()) {
                    albumArtist.splitCanonicalArtistNames().forEach {
                        artists.add(it.collapseSpaces().resolveArtistAlias().lowercase())
                    }
                }
                song.artistName.splitCanonicalArtistNames().forEach {
                    artists.add(it.collapseSpaces().resolveArtistAlias().lowercase())
                }
                song.genreName?.let { if (it.isNotBlank()) genres.add(it.lowercase()) }
            }
            return SyncRuleValidationData(
                folderSegments = folders,
                artistNames = artists,
                genreNames = genres,
                playlistNames = playlistNames.map { it.trim().lowercase() }.toSet()
            )
        }
    }
}

/**
 * Validation failures for a rules set. [invalidFolders]/[invalidArtists]/
 * [invalidGenres]/[invalidPlaylists] carry the raw entries the user typed
 * that do not resolve to anything; [builtinMisuse] is set when a built-in
 * playlist token is combined with anything else (built-ins can only be
 * used singularly, as the entire rule).
 */
data class SyncRuleErrors(
    val invalidFolders: List<String> = emptyList(),
    val invalidArtists: List<String> = emptyList(),
    val invalidGenres: List<String> = emptyList(),
    val invalidPlaylists: List<String> = emptyList(),
    val builtinMisuse: Boolean = false
) {
    val hasErrors: Boolean
        get() = builtinMisuse || invalidFolders.isNotEmpty() || invalidArtists.isNotEmpty() ||
            invalidGenres.isNotEmpty() || invalidPlaylists.isNotEmpty()
}

fun PlaylistSyncRules.validate(data: SyncRuleValidationData): SyncRuleErrors {
    val builtinCount = playlists.count { BuiltinPlaylist.fromToken(it) != null }

    val builtinMisuse = builtinCount > 1 ||
        (builtinCount == 1 && (playlists.size > 1 ||
            folders.isNotEmpty() || artists.isNotEmpty() || genres.isNotEmpty()))

    val invalidFolders = folders.filter { folder ->
        data.folderSegments.contains(folder.trim().lowercase()).not()
    }
    val invalidArtists = artists.filter { artist ->
        !data.artistNames.contains(artist.collapseSpaces().resolveArtistAlias().lowercase())
    }
    val invalidGenres = genres.filter { genre ->
        !data.genreNames.contains(genre.trim().lowercase())
    }
    val invalidPlaylists = playlists.filter { entry ->
        BuiltinPlaylist.fromToken(entry) == null &&
            !data.playlistNames.contains(entry.trim().lowercase())
    }

    return SyncRuleErrors(
        invalidFolders = invalidFolders,
        invalidArtists = invalidArtists,
        invalidGenres = invalidGenres,
        invalidPlaylists = invalidPlaylists,
        builtinMisuse = builtinMisuse
    )
}
