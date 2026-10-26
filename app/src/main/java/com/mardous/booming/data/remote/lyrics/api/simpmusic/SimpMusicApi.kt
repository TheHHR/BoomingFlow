package com.mardous.booming.data.remote.lyrics.api.simpmusic

import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.RateLimiter
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.data.remote.lyrics.model.SimpMusicResponse
import com.mardous.booming.util.Constants.SIMPLE_USER_AGENT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.userAgent

/**
 * SimpMusic lyrics API (api-lyrics.simpmusic.org). Returns synced LRC or
 * plain text.
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

        val response = client.get(SIMPMUSIC_API_URL) {
            userAgent(SIMPLE_USER_AGENT)
            parameter("title", title)
            parameter("artist", artist)
            parameter("limit", 1)
            timeout {
                connectTimeoutMillis = 5000
                socketTimeoutMillis = 10000
                requestTimeoutMillis = 15000
            }
        }
        if (rateLimiter.handleResponse(response)) return null
        if (response.status != HttpStatusCode.OK) return null

        val item = response.body<SimpMusicResponse>().data
            ?.firstOrNull { !it.syncedLyrics.isNullOrBlank() || !it.plainLyric.isNullOrBlank() }
            ?: return null

        val synced = item.syncedLyrics?.takeIf { it.isNotBlank() }
        if (synced != null) {
            return RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, synced))
        }
        val plain = item.plainLyric?.takeIf { it.isNotBlank() } ?: return null
        return if (plain.contains("[00:")) {
            RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, plain))
        } else {
            RawLyrics.Remote(plain = RawLyrics.Remote.Content(provider.displayName, plain))
        }
    }

    companion object {
        private const val SIMPMUSIC_API_URL = "https://api-lyrics.simpmusic.org/api/lyrics"
    }
}
