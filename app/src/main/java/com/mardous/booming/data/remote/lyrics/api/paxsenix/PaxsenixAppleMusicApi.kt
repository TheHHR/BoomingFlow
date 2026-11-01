package com.mardous.booming.data.remote.lyrics.api.paxsenix

import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.data.remote.lyrics.spatial.LyricsNormalizer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.userAgent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apple Music lyrics via Paxsenix + iTunes Search scoring, ported from
 * SpatialFlow's PaxsenixAppleMusicProvider.
 *
 * iTunes Search (public, tokenless) scores catalog results by title,
 * artist and duration; the track id then fetches raw TTML from
 * `/apple-music/lyrics?id=...&ttml=true` with a `/apple-music/cache/...`
 * fallback. Returns word-by-word TTML when available.
 */
class PaxsenixAppleMusicApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.AppleMusic

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        val durationSec = (song.duration / 1000).toInt()
        val query = "$title $artist"
        val trackId = findTrackId(query, title, artist, durationSec)
            ?: return null

        val ttml = try {
            client.get("$BASE_URL/apple-music/lyrics") {
                userAgent(APP_USER_AGENT)
                header(HttpHeaders.Accept, "application/json, text/plain, */*")
                parameter("id", trackId)
                parameter("ttml", "true")
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }.takeIf { it.status == HttpStatusCode.OK }?.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        val content = ttml?.let { extractTtml(it) }
            ?: fetchCachedTtml(trackId)
            ?: return null

        return RawLyrics.Remote(
            synced = RawLyrics.Remote.Content(provider.displayName, content)
        )
    }

    private suspend fun findTrackId(
        query: String,
        title: String,
        artist: String,
        durationSec: Int
    ): String? {
        // 1. iTunes Search with title/artist/duration scoring (SpatialFlow logic).
        try {
            val response = client.get(ITUNES_BASE_URL) {
                userAgent(CHROME_USER_AGENT)
                parameter("term", query)
                parameter("entity", "song")
                parameter("limit", "10")
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (response.status == HttpStatusCode.OK) {
                val root = runCatching {
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject
                }.getOrNull()
                val songs = root?.get("results")?.jsonArray
                if (songs != null && songs.isNotEmpty()) {
                    var bestId: String? = null
                    var bestScore = -1
                    for (element in songs) {
                        val item = element.jsonObject
                        val songId = item["trackId"]?.jsonPrimitive?.contentOrNull ?: continue
                        val name = item["trackName"]?.jsonPrimitive?.contentOrNull ?: ""
                        val artistName = item["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
                        val durMs = item["trackTimeMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

                        var score = 0
                        score += scoreName(name, title)
                        score += scoreName(artistName, artist)
                        if (durationSec > 0 && durMs > 0) {
                            val diff = kotlin.math.abs(durMs - durationSec * 1000L)
                            score += when {
                                diff < 3000 -> 10
                                diff < 10000 -> 5
                                else -> 0
                            }
                        }
                        if (score > bestScore) {
                            bestScore = score
                            bestId = songId
                        }
                    }
                    if (bestScore >= 12) return bestId
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fall through to Paxsenix search.
        }

        // 2. Paxsenix search fallback.
        val searchBody = PaxsenixClient.request(
            client, "/apple-music/search", listOf("q" to query)
        ) ?: return null
        return PaxsenixClient.extractFirstId(searchBody)
    }

    private fun scoreName(actual: String, expected: String): Int {
        return when {
            actual.equals(expected, ignoreCase = true) -> 20
            actual.contains(expected, ignoreCase = true) -> 10
            // SpatialFlow fallback for reversed containment.
            LyricsNormalizer.similarity(actual, expected) > 0.85f -> 5
            else -> 0
        }
    }

    private fun extractTtml(body: String): String? {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<tt") || trimmed.startsWith("<?xml")) return body
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            val content = (json["content"] ?: json["ttmlContent"])
                ?.jsonPrimitive?.contentOrNull
            content?.takeIf { it.startsWith("<tt") || it.startsWith("<?xml") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchCachedTtml(trackId: String): String? {
        return try {
            val response = client.get("$BASE_URL/apple-music/cache/$trackId") {
                userAgent(APP_USER_AGENT)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (response.status != HttpStatusCode.OK) return null
            val body = response.bodyAsText()
            body.takeIf { it.startsWith("<tt") || it.startsWith("<?xml") }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val BASE_URL = "https://lyrics.paxsenix.org"
        private const val ITUNES_BASE_URL = "https://itunes.apple.com/search"
        private const val APP_USER_AGENT = "BoomingFlow"
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
