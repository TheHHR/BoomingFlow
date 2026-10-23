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

suspend fun SpotifyTrack.resolveForPlayback(): OnlineQueueItem? {
    val key = id.ifEmpty { buildSearchQuery() }
    spotifyResolutionCache[key]?.let { return it }

    // Offline-first: if this Spotify track has a downloaded local file,
    // build the queue item straight from it — no network involved.
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

    val query = buildSearchQuery()
    val searchResult = YouTubeMusic.search(query, SearchFilter.SONGS).getOrNull()
        ?: YouTubeMusic.search(query).getOrNull()
        ?: return null

    val match = searchResult.items.filterIsInstance<SearchItem.Song>().firstOrNull()?.song
        ?: searchResult.items.filterIsInstance<SearchItem.TopResult>().firstOrNull()?.song
        ?: return null

    val item = buildOnlineQueueItem(
        videoId = match.videoId,
        title = match.title,
        artist = match.artist,
        album = null,
        durationMs = match.durationMs,
        thumbnailUrl = match.thumbnailUrl
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
