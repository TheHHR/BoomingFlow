package com.mardous.booming.online.spotify

import android.net.Uri
import com.mardous.booming.online.data.innertube.SearchFilter
import com.mardous.booming.online.data.innertube.SearchItem
import com.mardous.booming.online.data.innertube.YouTubeMusic
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.data.spotify.buildSearchQuery
import com.mardous.booming.online.data.spotify.thumbnailUrl
import com.mardous.booming.online.download.DownloadRegistry
import com.mardous.booming.online.playback.OnlineQueueItem
import com.mardous.booming.online.playback.buildOnlineQueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val spotifyResolutionCache = ConcurrentHashMap<String, OnlineQueueItem>()

/** YouTube video id -> Spotify track id, recorded while resolving for playback. */
private val videoIdToTrackId = ConcurrentHashMap<String, String>()

fun spotifyTrackIdFor(videoId: String): String? = videoIdToTrackId[videoId]

/** Records that [spotifyTrackId] backs [videoId] so later downloads register both keys. */
fun rememberVideoIdMapping(videoId: String, spotifyTrackId: String) {
    if (spotifyTrackId.isNotBlank()) {
        videoIdToTrackId[videoId] = spotifyTrackId
    }
}

suspend fun SpotifyTrack.resolveForPlayback(): OnlineQueueItem? {
    val key = id.ifEmpty { buildSearchQuery() }

    // Offline-first: if this Spotify track has a downloaded local file,
    // build the queue item straight from it — no network involved. This
    // check must run BEFORE the resolution cache: a cached entry may be a
    // stale streaming item (https artwork + innertube uri) that would show
    // no cover and fail playback while offline.
    DownloadRegistry.spotifyTrackUri(id)?.let { uri ->
        val item = buildOnlineQueueItem(
            videoId = id,
            title = name,
            artist = artists.joinToString { it.name },
            album = album?.name,
            durationMs = durationMs.toLong(),
            thumbnailUrl = thumbnailUrl(),
            localUri = Uri.parse(uri)
        )
        spotifyResolutionCache[key] = item
        return item
    }

    val cached = spotifyResolutionCache[key]
    if (cached != null) {
        // A cached local item whose download was deleted is stale: re-resolve.
        val isLocal = cached.song.uri.scheme == "content"
        if (!isLocal) return cached
        // Local but no longer in the registry — fall through to a fresh resolve.
    }

    val query = buildSearchQuery()
    val searchResult = YouTubeMusic.search(query, SearchFilter.SONGS).getOrNull()
        ?: YouTubeMusic.search(query).getOrNull()
        ?: return null

    val match = searchResult.items.filterIsInstance<SearchItem.Song>().firstOrNull()?.song
        ?: searchResult.items.filterIsInstance<SearchItem.TopResult>().firstOrNull()?.song
        ?: return null

    if (id.isNotBlank()) {
        rememberVideoIdMapping(match.videoId, id)
    }

    val item = buildOnlineQueueItem(
        videoId = match.videoId,
        title = match.title,
        artist = match.artist,
        album = null,
        durationMs = match.durationMs,
        thumbnailUrl = thumbnailUrl() ?: match.thumbnailUrl
    )
    spotifyResolutionCache[key] = item
    return item
}

suspend fun List<SpotifyTrack>.resolveBatchForPlayback(limit: Int = 10): List<OnlineQueueItem> =
    withContext(Dispatchers.IO) {
        coroutineScope {
            take(limit).map { track -> async { track.resolveForPlayback() } }
                .awaitAll()
                .filterNotNull()
        }
    }
