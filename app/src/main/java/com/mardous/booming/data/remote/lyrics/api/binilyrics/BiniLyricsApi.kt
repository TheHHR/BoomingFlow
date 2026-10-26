package com.mardous.booming.data.remote.lyrics.api.binilyrics

import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.RateLimiter
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.util.Constants.SIMPLE_USER_AGENT
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.userAgent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * BiniLyrics (lyrics-api.binimum.org). A search returns candidates with a
 * per-track lyrics download URL; the downloaded content is word/syllable
 * TTML, line-synced LRC or plain text.
 */
class BiniLyricsApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.BiniLyrics

    private val rateLimiter = RateLimiter("BiniLyricsApi")

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        if (rateLimiter.isRateLimited) return null

        val targetDuration = (song.duration / 1000).toInt()

        val searchResponse = client.get(BINI_API_URL) {
            userAgent(SIMPLE_USER_AGENT)
            parameter("title", title)
            parameter("artist", artist)
            timeout {
                connectTimeoutMillis = 5000
                socketTimeoutMillis = 10000
                requestTimeoutMillis = 15000
            }
        }
        if (rateLimiter.handleResponse(searchResponse)) return null
        if (searchResponse.status != HttpStatusCode.OK) return null

        val results = runCatching {
            (Json.parseToJsonElement(searchResponse.bodyAsText()) as? JsonObject)
                ?.get("results") as? JsonArray
        }.getOrNull() ?: return null

        val candidates = results.mapNotNull { it as? JsonObject }
        if (candidates.isEmpty()) return null

        val best = if (targetDuration > 0) {
            candidates.minByOrNull { candidate ->
                abs((candidate.jsonLong("duration") - targetDuration))
            } ?: candidates.first()
        } else {
            candidates.first()
        }

        val lyricsUrl = best["lyricsUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null

        val lyricsResponse = client.get(lyricsUrl) {
            userAgent(SIMPLE_USER_AGENT)
            timeout {
                connectTimeoutMillis = 5000
                socketTimeoutMillis = 10000
                requestTimeoutMillis = 15000
            }
        }
        if (lyricsResponse.status != HttpStatusCode.OK) return null

        val content = lyricsResponse.bodyAsText().trim()
        if (content.isBlank()) return null

        return when {
            content.contains("<tt") || content.contains("<p begin=") || content.contains("xmlns=") ->
                RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, content))
            content.contains("[00:") ->
                RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, content))
            else ->
                RawLyrics.Remote(plain = RawLyrics.Remote.Content(provider.displayName, content))
        }
    }

    private fun JsonObject.jsonLong(name: String): Long =
        this[name]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L

    companion object {
        private const val BINI_API_URL = "https://lyrics-api.binimum.org/lyrics"
    }
}
