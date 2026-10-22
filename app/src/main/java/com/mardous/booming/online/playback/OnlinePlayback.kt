@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mardous.booming.online.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.mardous.booming.online.data.innertube.OnlineSong
import com.mardous.booming.online.data.innertube.YouTubeMusic
import com.mardous.booming.online.data.network.FastFallbackDns
import com.mardous.booming.online.util.StreamClientUtils
import com.mardous.booming.data.model.UnindexedSong
import com.mardous.booming.playback.RESOLVED_FROM_FILE
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

const val ONLINE_MEDIA_ID_PREFIX = "yt:"

fun onlineMediaId(videoId: String) = "$ONLINE_MEDIA_ID_PREFIX$videoId"

fun onlineContentUri(videoId: String): Uri = Uri.parse("innertube://$videoId")

fun isOnlineContentUri(uri: Uri?): Boolean = uri?.scheme == "innertube"

private val streamRetryClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(FastFallbackDns)
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val profiled = StreamClientUtils.applyRequestProfile(request.newBuilder(), request).build()
            val isMedia = request.url.host?.contains("googlevideo.com") == true
            var response = chain.proceed(profiled)
            if (isMedia && response.code == 403) {
                response.close()
                response = chain.proceed(
                    request.newBuilder()
                        .header("User-Agent", StreamClientUtils.USER_AGENT_ANDROID_VR)
                        .removeHeader("Origin")
                        .removeHeader("Referer")
                        .build()
                )
            }
            response
        }
        .build()
}

fun createStreamingDataSourceFactory(context: Context): DataSource.Factory {
    val httpFactory = OkHttpDataSource.Factory(streamRetryClient)
        .setUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

    val baseFactory = DefaultDataSource.Factory(context, httpFactory)

    return ResolvingDataSource.Factory(baseFactory) { dataSpec ->
        val uri = dataSpec.uri
        if (uri.scheme != "innertube") {
            dataSpec
        } else {
            val videoId = uri.host
                ?: throw IOException("innertube URI is missing a video id: $uri")
            val url = runBlocking {
                YouTubeMusic.getStreamUrl(videoId = videoId)
            }.getOrElse { cause ->
                throw IOException("Failed to resolve a stream for $videoId", cause)
            }
            dataSpec.buildUpon().setUri(url).build()
        }
    }
}

data class OnlineQueueItem(
    val mediaItem: MediaItem,
    val song: UnindexedSong,
)

fun buildOnlineQueueItem(online: OnlineSong): OnlineQueueItem {
    return buildOnlineQueueItem(
        videoId = online.videoId,
        title = online.title,
        artist = online.artist,
        album = online.albumName,
        durationMs = online.durationMs,
        thumbnailUrl = online.thumbnailUrl
    )
}

fun buildOnlineQueueItem(
    videoId: String,
    title: String,
    artist: String,
    album: String? = null,
    durationMs: Long,
    thumbnailUrl: String?,
): OnlineQueueItem {
    val mediaId = onlineMediaId(videoId)
    val artworkUri = thumbnailUrl?.let(Uri::parse)
    val mediaItem = MediaItem.Builder()
        .setUri(onlineContentUri(videoId))
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setTitle(title)
                .setSubtitle(artist)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri)
                .setDurationMs(durationMs.coerceAtLeast(0))
                .setExtras(
                    Bundle().apply { putBoolean(RESOLVED_FROM_FILE, true) }
                )
                .build()
        )
        .build()

    val song = UnindexedSong(
        uri = artworkUri ?: onlineContentUri(videoId),
        id = videoId.hashCode().toLong(),
        data = mediaId,
        title = title,
        size = 0,
        duration = durationMs,
        albumName = album.orEmpty(),
        artistName = artist
    )

    return OnlineQueueItem(mediaItem, song)
}
