package com.mardous.booming.data.remote.lyrics.api.kouzu

import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Kouzu MLC word-by-word karaoke lyrics, ported from SpatialFlow's
 * KouzuLyricsProvider. Direct videoId lookup (available for streaming
 * songs via their online id) with a search-endpoint fallback, then the
 * syllable JSON is turned into Enhanced LRC inline.
 *
 * Endpoints: GET https://mlc.kouzu.in/api/lyrics/{videoId}
 *            GET https://mlc.kouzu.in/api/search?q=...&limit=3
 */
class KouzuApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.Kouzu

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        val videoId = onlineVideoId(song)
        if (!videoId.isNullOrBlank()) {
            fetchByVideoId(videoId, title, artist)?.let { return it }
        }

        if (title.isBlank() || artist.isBlank()) return null
        return try {
            val response = client.get("$BASE_URL/search") {
                userAgent(SIMPLE_USER_AGENT)
                parameter("q", "$title $artist")
                parameter("limit", 3)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (response.status != HttpStatusCode.OK) return null
            val root = runCatching {
                Json.parseToJsonElement(response.bodyAsText()).jsonArray
            }.getOrNull() ?: return null
            for (element in root) {
                val id = (element as? JsonObject)
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: continue
                fetchByVideoId(id, title, artist)?.let { return it }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchByVideoId(
        videoId: String,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        return try {
            val response = client.get("$BASE_URL/lyrics/$videoId") {
                userAgent(SIMPLE_USER_AGENT)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 8000
                    requestTimeoutMillis = 10000
                }
            }
            if (response.status != HttpStatusCode.OK) return null
            val lrc = parseResponse(response.bodyAsText())?.takeIf { it.isNotBlank() }
                ?: return null
            RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, lrc))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun parseResponse(jsonStr: String): String? {
        return runCatching {
            val root = Json.parseToJsonElement(jsonStr).jsonObject
            val lyricsArr = root["lyrics"]?.jsonArray ?: return null
            if (lyricsArr.isEmpty()) return null

            val isWordType = root["type"]?.jsonPrimitive?.contentOrNull
                .equals("Word", ignoreCase = true)
            val sb = StringBuilder()
            for (element in lyricsArr) {
                val lineObj = element.jsonObject
                val timeMs = lineObj["time"]?.jsonPrimitive?.longOrNull ?: continue
                sb.append(formatLrcTimestamp(timeMs, bracketed = true))
                val syllables = lineObj["syllabus"]?.jsonArray
                if (isWordType && syllables != null && syllables.isNotEmpty()) {
                    for (syl in syllables) {
                        val sylObj = syl.jsonObject
                        val sylText = sylObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        val sylTimeMs = sylObj["time"]?.jsonPrimitive?.longOrNull ?: 0L
                        sb.append(formatLrcTimestamp(sylTimeMs, bracketed = false))
                            .append(sylText)
                    }
                } else {
                    sb.append(lineObj["text"]?.jsonPrimitive?.contentOrNull ?: "")
                }
                sb.append("\n")
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun formatLrcTimestamp(timeMs: Long, bracketed: Boolean): String {
        val safeTime = timeMs.coerceAtLeast(0L)
        val minutes = safeTime / 60000L
        val seconds = (safeTime % 60000L) / 1000L
        val hundredths = (safeTime % 1000L) / 10L
        val formatted = String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)
        return if (bracketed) "[$formatted]" else "<$formatted>"
    }

    private fun onlineVideoId(song: Song): String? {
        // Streaming songs carry the video id in the innertube pseudo-uri
        // host, or the artwork https URL with a "yt:" data marker.
        val uri = song.uri
        uri?.host?.takeIf { it.isNotBlank() }?.let { return it }
        return song.data.removePrefix("yt:")
            .takeIf { song.data.startsWith("yt:") && it.isNotBlank() }
    }

    companion object {
        private const val BASE_URL = "https://mlc.kouzu.in/api"
    }
}
