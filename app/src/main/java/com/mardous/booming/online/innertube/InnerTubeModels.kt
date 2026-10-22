package com.mardous.booming.online.data.innertube

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.mardous.booming.online.model.SongItem

/**
 * Data models for YouTube Music online content.
 * These are separate from the local SongItem to cleanly handle online vs offline songs.
 */

/** Represents an online song from YouTube Music */
@Immutable
data class OnlineSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val albumName: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val duration: String? = null,       // "3:45" formatted
    val durationMs: Long = 0,           // milliseconds
    val thumbnailUrl: String? = null,
    val animatedThumbnailUrl: String? = null,
    val isExplicit: Boolean = false,
    val isVideo: Boolean = false,
    val thumbnailRatio: Float? = null
)

fun OnlineSong.toSongItem(): SongItem {
    return SongItem.createOnlineSong(
        videoId = videoId,
        title = title,
        artist = artist,
        streamUrl = null,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        artistId = artistId,
        animatedThumbnailUrl = animatedThumbnailUrl,
        isExplicit = isExplicit
    ).apply {
        this.albumName = this@toSongItem.albumName
    }
}

/** Represents an online album */
@Immutable
data class OnlineAlbum(
    val browseId: String,
    val playlistId: String? = null,
    val title: String,
    val artists: List<OnlineArtistRef>,
    val year: Int? = null,
    val thumbnailUrl: String? = null,
    val songCount: String? = null
)

/** Represents an online artist */
@Immutable
data class OnlineArtist(
    val browseId: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val subscriberCount: String? = null,
    val shuffleEndpoint: String? = null,
    val radioEndpoint: String? = null,
    val isSubscribed: Boolean = false
)

/** Lightweight artist reference (used in songs/albums) */
@Immutable
data class OnlineArtistRef(
    val name: String,
    val id: String? = null
)

@Immutable
data class OnlinePlaylist(
    val playlistId: String,
    val title: String,
    val author: OnlineArtistRef? = null,
    val songCount: String? = null,
    val thumbnailUrl: String? = null,
    val color: Long? = null
)

/** Union type for search results */
@Stable
sealed class SearchItem {
    @Stable
    data class TopResult(
        val title: String,
        val subtitle: String,
        val itemType: String,
        val thumbnailUrl: String?,
        val song: OnlineSong? = null,
        val album: OnlineAlbum? = null,
        val artist: OnlineArtist? = null,
        val playlist: OnlinePlaylist? = null
    ) : SearchItem()

    @Stable
    data class Header(val title: String) : SearchItem()

    @Stable
    data class Song(
        val song: OnlineSong,
        val badge: String? = null,
        val typeText: String? = null
    ) : SearchItem()

    @Stable
    data class Album(
        val album: OnlineAlbum,
        val badge: String? = null
    ) : SearchItem()

    @Stable
    data class Artist(
        val artist: OnlineArtist
    ) : SearchItem()

    @Stable
    data class Playlist(
        val playlist: OnlinePlaylist,
        val badge: String? = null
    ) : SearchItem()
}

/** Deterministic stable key for Jetpack Compose LazyColumn/LazyRow and in-parser deduplication */
val SearchItem.stableKey: String
    get() = when (this) {
        is SearchItem.Song -> "song_${song.videoId.ifBlank { "${song.title}_${song.artist}" }}"
        is SearchItem.Album -> "album_${album.browseId.ifBlank { album.title }}"
        is SearchItem.Artist -> "artist_${artist.browseId.ifBlank { artist.title }}"
        is SearchItem.Playlist -> "playlist_${playlist.playlistId.ifBlank { playlist.title }}"
        is SearchItem.TopResult -> "top_${title}_${subtitle}"
        is SearchItem.Header -> "header_${title}"
    }

const val THUMBNAIL_RATIO_SQUARE = 1f
const val THUMBNAIL_RATIO_LANDSCAPE = 16f / 9f

val SearchItem.isWidescreenVideo: Boolean
    get() = when (this) {
        is SearchItem.Song -> song.isVideo ||
                song.thumbnailUrl?.contains("ytimg.com/vi/") == true ||
                song.thumbnailUrl?.contains("i.ytimg.com/vi/") == true
        else -> false
    }

fun SearchItem.preferredThumbnailRatio(sectionTitle: String? = null): Float {
    if (this is SearchItem.Artist) return THUMBNAIL_RATIO_SQUARE
    if (sectionTitle?.contains("video", ignoreCase = true) == true) return THUMBNAIL_RATIO_LANDSCAPE
    return when (this) {
        is SearchItem.Song -> {
            if (isWidescreenVideo) {
                THUMBNAIL_RATIO_LANDSCAPE
            } else {
                song.thumbnailRatio ?: THUMBNAIL_RATIO_SQUARE
            }
        }
        else -> THUMBNAIL_RATIO_SQUARE
    }
}

/** Search result container */
data class SearchResult(
    val items: List<SearchItem>,
    val continuation: String? = null
)

/** Stream URL data extracted from player response */
data class StreamData(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val contentLength: Long?,
    val audioQuality: String?
)

/** Player response containing streaming data */
data class PlayerResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val animatedThumbnailUrl: String? = null,
    val durationMs: Long,
    val streams: List<StreamData>,
    val playbackUrl: String? = null,
    val watchtimeUrl: String? = null,
    val likesCount: String? = null
)

/** Home feed section (carousel) */
@Stable
data class HomeSection(
    val title: String,
    val items: List<SearchItem>,
    val browseEndpoint: String? = null,
    val params: String? = null
)

/** Home page data */
@Stable
data class HomePage(
    val sections: List<HomeSection>,
    val continuation: String? = null,
    val moods: List<String> = emptyList()
)

/** Album page data */
@Stable
data class AlbumPage(
    val album: OnlineAlbum,
    val songs: List<OnlineSong>,
    val otherVersions: List<OnlineAlbum> = emptyList()
)

/** Artist page data */
@Stable
data class ArtistPage(
    val artist: OnlineArtist,
    val sections: List<HomeSection>,
    val description: String? = null
)

/** Playlist page data */
@Immutable
data class PlaylistPage(
    val playlist: OnlinePlaylist,
    val songs: List<OnlineSong>,
    val continuation: String? = null
)

/** Search filter types */
enum class SearchFilter(val value: String) {
    SONGS("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
    ALBUMS("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
    ARTISTS("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
    PLAYLISTS("EgeKAQQoAEABagwQDhAKEAMQBRAJEAQ%3D"),
}

/** Represents a user profile */
data class UserProfile(
    val name: String,
    val handle: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null
)

/** Represents an autoplay recommendation chip */
data class AutoplayChip(
    val title: String,
    val params: String?,
    val playlistId: String?,
    val isSelected: Boolean,
    val videoId: String? = null
)

/** Represents the results of a related/next API call */
data class RelatedSongsResult(
    val songs: List<OnlineSong>,
    val chips: List<AutoplayChip>
)



