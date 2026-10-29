package com.mardous.booming.online.data.spotify

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Token ────────────────────────────────────────────────────────────────────

@Serializable
data class SpotifyInternalToken(
    val accessToken: String,
    val accessTokenExpirationTimestampMs: Long,
    val isAnonymous: Boolean = false,
    val clientId: String = "",
)

// ── User ─────────────────────────────────────────────────────────────────────

@Immutable
@Serializable
data class SpotifyUser(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
    val images: List<SpotifyImage> = emptyList(),
)

// ── Image ────────────────────────────────────────────────────────────────────

@Immutable
@Serializable
data class SpotifyImage(
    val url: String,
    val height: Int? = null,
    val width: Int? = null,
)

// ── Track ────────────────────────────────────────────────────────────────────

@Immutable
@Serializable
data class SpotifyTrack(
    val id: String = "",
    val name: String = "",
    val artists: List<SpotifySimpleArtist> = emptyList(),
    val album: SpotifySimpleAlbum? = null,
    @SerialName("duration_ms") val durationMs: Int = 0,
    val explicit: Boolean = false,
    @SerialName("is_local") val isLocal: Boolean = false,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    val uri: String? = null,
    val popularity: Int? = null,
)

@Immutable
@Serializable
data class SpotifySimpleArtist(
    val id: String? = null,
    val name: String = "",
    val uri: String? = null,
)

@Immutable
@Serializable
data class SpotifySimpleAlbum(
    val id: String = "",
    val name: String = "",
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("album_type") val albumType: String? = null,
    val artists: List<SpotifySimpleArtist> = emptyList(),
    val uri: String? = null,
)

/** Wrapper for the /me/tracks endpoint which returns SavedTrack objects */
@Immutable
@Serializable
data class SpotifySavedTrack(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpotifyTrack,
)

// ── Playlist ─────────────────────────────────────────────────────────────────

@Immutable
@Serializable
data class SpotifyPlaylist(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val owner: SpotifyPlaylistOwner? = null,
    val tracks: SpotifyPlaylistTracksRef? = null,
    val uri: String? = null,
    val public: Boolean? = null,
    val collaborative: Boolean = false,
    @SerialName("snapshot_id") val snapshotId: String? = null,
)

@Immutable
@Serializable
data class SpotifyPlaylistOwner(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val uri: String? = null,
)

/** Lightweight track count reference returned in playlist listings */
@Immutable
@Serializable
data class SpotifyPlaylistTracksRef(
    val total: Int? = null,
    val href: String? = null,
)

/**
 * Full playlist track item returned by fetchPlaylist GQL endpoint.
 * [uid] is the playlist-scoped item identifier required by GQL mutations.
 */
@Immutable
@Serializable
data class SpotifyPlaylistTrack(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpotifyTrack? = null,
    @SerialName("is_local") val isLocal: Boolean = false,
    val uid: String? = null,
)

// ── Paging ───────────────────────────────────────────────────────────────────

@Immutable
@Serializable
data class SpotifyPaging<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val limit: Int = 50,
    val offset: Int = 0,
    val next: String? = null,
)

@Immutable
@Serializable
data class SpotifySearchResults(
    val tracks: List<SpotifyTrack> = emptyList(),
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    val canLoadMore: Boolean = false
)

@Immutable
@Serializable
data class SpotifyArtist(
    val id: String = "",
    val name: String = "",
    val imageUrl: String? = null,
)

@Immutable
@Serializable
data class SpotifyAlbum(
    val id: String = "",
    val name: String = "",
    val artistName: String = "",
    val year: Int? = null,
    val imageUrl: String? = null,
)

// ── Library Item (for folder-aware library tree) ─────────────────────────────

@Immutable
sealed class SpotifyLibraryItem {
    @Immutable
    data class Playlist(val playlist: SpotifyPlaylist) : SpotifyLibraryItem()
    @Immutable
    data class Folder(val folder: SpotifyLibraryFolder) : SpotifyLibraryItem()
}

@Immutable
data class SpotifyLibraryFolder(
    val uri: String,
    val name: String,
    val totalChildren: Int,
)

/** Personalized content returned by Spotify's web-player Home query. */
@Stable
@Serializable
data class SpotifyHomeFeed(
    val greeting: String? = null,
    val sections: List<SpotifyHomeSection> = emptyList(),
)

@Stable
@Serializable
data class SpotifyHomeSection(
    val uri: String = "",
    val title: String? = null,
    val totalCount: Int = 0,
    val items: List<SpotifyHomeItem> = emptyList(),
)

@Stable
@Serializable
sealed class SpotifyHomeItem {
    abstract val uri: String
    abstract val id: String

    @Stable
    @Serializable
    data class Playlist(
        override val uri: String,
        override val id: String,
        val name: String,
        val description: String? = null,
        val imageUrl: String? = null,
        val ownerName: String? = null,
    ) : SpotifyHomeItem()

    @Stable
    @Serializable
    data class Album(
        override val uri: String,
        override val id: String,
        val name: String,
        val artists: List<SpotifySimpleArtist> = emptyList(),
        val imageUrl: String? = null,
    ) : SpotifyHomeItem()

    @Stable
    @Serializable
    data class Artist(
        override val uri: String,
        override val id: String,
        val name: String,
        val imageUrl: String? = null,
    ) : SpotifyHomeItem()
}
