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
import org.json.JSONArray
import org.json.JSONObject

/**
 * The built-in (smart) song lists that a synced playlist may reference.
 * A built-in is a whole rule by itself: it must be the only entry in the
 * playlists field and no other field (folders/artists/genres) may be
 * filled alongside it.
 *
 * The enum name is the stable, locale-proof identifier; [token] is what
 * the user types (matched case-insensitively).
 */
enum class BuiltinPlaylist(val token: String) {
    TOP_TRACKS("top tracks"),
    HISTORY("history"),
    LAST_ADDED("last added");

    companion object {
        fun fromToken(raw: String): BuiltinPlaylist? =
            entries.firstOrNull { it.token.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * Rules that make a playlist "synced": every library song matching at
 * least one rule is added to the playlist, and stored songs that no
 * longer match are removed on every sync pass.
 *
 * Within each field the values are alternatives (OR). Across fields the
 * match is also OR: a song belongs to the playlist when it matches any
 * non-empty field or is a member of a referenced playlist. A playlist
 * with rules that match nothing is simply empty.
 *
 * - Folders match by folder NAME anywhere in the song path (case-
 *   insensitive), subfolders included.
 * - Artists match with the same semantics as the Artists tab: names are
 *   split by the user-configured separators, merged aliases resolve to
 *   their canonical spelling, and both the album-artist tag (with the
 *   album-derived fallback) and the song's own artist tag are considered.
 * - Genres match the song's genre tag (case-insensitive).
 * - Playlists reference other playlists by name (case-insensitive);
 *   [BuiltinPlaylist] tokens reference the built-in smart lists, which
 *   may only be used singularly (validation enforces this).
 */
data class PlaylistSyncRules(
    val folders: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val playlists: List<String> = emptyList()
) {

    fun isValid(): Boolean =
        folders.isNotEmpty() || artists.isNotEmpty() ||
            genres.isNotEmpty() || playlists.isNotEmpty()

    fun matches(song: Song): Boolean {
        if (folders.isNotEmpty() && matchesFolders(song)) return true
        if (artists.isNotEmpty() && matchesArtists(song)) return true
        if (genres.isNotEmpty() && matchesGenres(song)) return true
        return false
    }

    private fun matchesFolders(song: Song): Boolean {
        if (song.data.isEmpty()) return false
        val segments = song.data.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        return folders.any { folder ->
            segments.any { it.equals(folder, ignoreCase = true) }
        }
    }

    private fun matchesArtists(song: Song): Boolean {
        val canonicalRule = artists.map { it.collapseSpaces().resolveArtistAlias().lowercase() }
        val songNames = buildList {
            val albumArtist = song.albumArtistName?.collapseSpaces()
            if (!albumArtist.isNullOrEmpty()) {
                addAll(albumArtist.splitCanonicalArtistNames())
            }
            addAll(song.artistName.splitCanonicalArtistNames())
        }
        return songNames.any { name ->
            canonicalRule.contains(name.collapseSpaces().resolveArtistAlias().lowercase())
        }
    }

    private fun matchesGenres(song: Song): Boolean =
        song.genreName != null &&
            genres.any { it.equals(song.genreName, ignoreCase = true) }

    fun serialize(): String {
        val json = JSONObject()
        json.put(KEY_FOLDERS, JSONArray(folders))
        json.put(KEY_ARTISTS, JSONArray(artists))
        json.put(KEY_GENRES, JSONArray(genres))
        json.put(KEY_PLAYLISTS, JSONArray(playlists))
        return json.toString()
    }

    companion object {
        private const val KEY_FOLDERS = "folders"
        private const val KEY_ARTISTS = "artists"
        private const val KEY_GENRES = "genres"
        private const val KEY_PLAYLISTS = "playlists"

        fun deserialize(json: String?): PlaylistSyncRules? {
            if (json.isNullOrEmpty()) return null
            return runCatching {
                val obj = JSONObject(json)
                PlaylistSyncRules(
                    folders = obj.optStringArray(KEY_FOLDERS),
                    artists = obj.optStringArray(KEY_ARTISTS),
                    genres = obj.optStringArray(KEY_GENRES),
                    playlists = obj.optStringArray(KEY_PLAYLISTS)
                )
            }.getOrNull()
        }

        /**
         * Parses the raw comma-separated dialog input. Empty entries are
         * dropped, so "Eminem, Rihana" and "Eminem,Rihana" are equivalent.
         */
        fun parse(folders: String?, artists: String?, genres: String?, playlists: String? = null): PlaylistSyncRules {
            fun parseField(raw: String?): List<String> =
                raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            return PlaylistSyncRules(
                folders = parseField(folders),
                artists = parseField(artists),
                genres = parseField(genres),
                playlists = parseField(playlists)
            )
        }

        private fun JSONObject.optStringArray(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i)
                    if (value.isNotEmpty()) add(value)
                }
            }
        }
    }
}
