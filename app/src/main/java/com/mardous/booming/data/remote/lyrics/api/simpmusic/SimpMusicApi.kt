package com.mardous.booming.data.remote.lyrics.api.simpmusic

import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.RateLimiter
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * SimpMusic lyrics (api-lyrics.simpmusic.org), ported from SpatialFlow's
 * SimpMusicProvider: first candidate with synced or plain content wins,
 * synced preferred over plain.
 */
class SimpMusicApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.SimpMusic

    private val rateLimiter = RateLimiter("SimpMusicApi")

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        if (rateLimiter.isRateLimited) return null

        return try {
            val response = client.get("$BASE_URL/api/lyrics") {
                parameter("title", title)
                parameter("artist", artist)
                val videoId = onlineVideoId(song)
                if (!videoId.isNullOrBlank()) parameter("videoId", videoId)
                parameter("limit", 1)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (rateLimiter.handleResponse(response)) return null
            if (response.status != HttpStatusCode.OK) return null

            val apiResponse = response.body<SimpMusicResponse>()
            val items = apiResponse.data
            if (items.isNullOrEmpty()) return null

            val item = items.firstOrNull { !it.syncedLyrics.isNullOrBlank() || !it.plainLyric.isNullOrBlank() }
                ?: items.first()

            val synced = item.syncedLyrics?.takeIf { it.isNotBlank() }
            if (synced != null) {
                return RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, synced))
            }
            val plain = item.plainLyric?.takeIf { it.isNotBlank() } ?: return null
            if (plain.contains("[00:")) {
                RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, plain))
            } else {
                RawLyrics.Remote(plain = RawLyrics.Remote.Content(provider.displayName, plain))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun onlineVideoId(song: Song): String? {
        val uri = song.uri
        uri?.host?.takeIf { it.isNotBlank() }?.let { return it }
        return song.data.removePrefix("yt:")
            .takeIf { song.data.startsWith("yt:") && it.isNotBlank() }
    }

    companion object {
        private const val BASE_URL = "https://api-lyrics.simpmusic.org"
    }
}

@Serializable
private data class SimpMusicResponse(
    val type: String? = null,
    val success: Boolean? = null,
    val data: List<SimpMusicItem>? = null
)

@Serializable
private data class SimpMusicItem(
    val id: String? = null,
    val videoId: String? = null,
    val songTitle: String? = null,
    val artistName: String? = null,
    val plainLyric: String? = null,
    val syncedLyrics: String? = null
)
