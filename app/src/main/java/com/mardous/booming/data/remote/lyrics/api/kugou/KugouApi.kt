package com.mardous.booming.data.remote.lyrics.api.kugou

import android.util.Base64
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.RateLimiter
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.util.Constants.SIMPLE_USER_AGENT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.http.userAgent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

/**
 * KuGou lyrics (Chinese catalog). Queries the modern search + song data
 * endpoints first and falls back to the legacy search/download API, which
 * returns Base64-encoded LRC content. The legacy endpoint is reached over
 * https because this app blocks cleartext traffic.
 */
class KugouApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.KuGou

    private val rateLimiter = RateLimiter("KugouApi")

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        if (rateLimiter.isRateLimited) return null
        if (title.isBlank() && artist.isBlank()) return null
        return modernLyrics(song, title, artist) ?: legacyLyrics(song, title, artist)
    }

    private suspend fun modernLyrics(song: Song, title: String, artist: String): RawLyrics.Remote? {
        return try {
            val searchResponse = client.get(SEARCH_URL) {
                userAgent(SIMPLE_USER_AGENT)
                parameter("plat", 0)
                parameter("keyword", "$title $artist")
                parameter("pagesize", 5)
                parameter("version", 9108)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (rateLimiter.handleResponse(searchResponse)) return null
            if (searchResponse.status != HttpStatusCode.OK) return null

            val searchRoot = searchResponse.body<JsonElement>() as? JsonObject ?: return null
            if (searchRoot["status"]?.jsonPrimitive?.intOrNull != 1) return null
            val candidates = (searchRoot["data"] as? JsonObject)?.get("info").let { array ->
                (array as? JsonArray)?.mapNotNull { candidate -> candidate as? JsonObject }
            } ?: return null
            if (candidates.isEmpty()) return null

            val durationSec = song.duration / 1000
            val best = candidates.minByOrNull { candidate ->
                abs(candidate.jsonLong("duration") - durationSec)
            } ?: return null
            val hash = best["hash"]?.jsonPrimitive?.contentOrNull ?: return null
            val albumId = best["album_id"]?.jsonPrimitive?.contentOrNull

            val dataResponse = client.get(DATA_URL) {
                userAgent(SIMPLE_USER_AGENT)
                parameter("r", "play/getdata")
                parameter("hash", hash)
                albumId?.let { parameter("album_id", it) }
                parameter("mid", 1)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (dataResponse.status != HttpStatusCode.OK) return null

            val dataRoot = dataResponse.body<JsonElement>() as? JsonObject ?: return null
            if (dataRoot["status"]?.jsonPrimitive?.intOrNull != 1) return null
            val lyrics = (dataRoot["data"] as? JsonObject)?.get("lyrics")
                ?.jsonPrimitive?.contentOrNull?.trim()
                ?: return null
            if (lyrics.isBlank()) return null
            buildRemote(lyrics)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun legacyLyrics(song: Song, title: String, artist: String): RawLyrics.Remote? {
        return try {
            val searchResponse = client.get(LEGACY_BASE + "search") {
                userAgent(SIMPLE_USER_AGENT)
                parameter("ver", 1)
                parameter("man", "yes")
                parameter("client", "pc")
                parameter("keyword", "$title $artist")
                parameter("duration", song.duration)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (searchResponse.status != HttpStatusCode.OK) return null

            val searchRoot = searchResponse.body<JsonElement>() as? JsonObject ?: return null
            val candidates = (searchRoot["candidates"] as? JsonArray)
                ?.mapNotNull { candidate -> candidate as? JsonObject } ?: return null
            if (candidates.isEmpty()) return null

            val best = candidates.minByOrNull { candidate ->
                abs(candidate.jsonLong("duration") - song.duration)
            } ?: return null
            val id = best["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val accessKey = best["accesskey"]?.jsonPrimitive?.contentOrNull ?: return null

            val downloadResponse = client.get(LEGACY_BASE + "download") {
                userAgent(SIMPLE_USER_AGENT)
                parameter("ver", 1)
                parameter("client", "pc")
                parameter("id", id)
                parameter("accesskey", accessKey)
                parameter("fmt", "lrc")
                parameter("charset", "utf8")
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (downloadResponse.status != HttpStatusCode.OK) return null

            val downloadRoot = downloadResponse.body<JsonElement>() as? JsonObject ?: return null
            val encoded = downloadRoot["content"]?.jsonPrimitive?.contentOrNull ?: return null
            val lyrics = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (lyrics.isBlank()) return null
            buildRemote(lyrics)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun buildRemote(lyrics: String): RawLyrics.Remote {
        val content = RawLyrics.Remote.Content(provider.displayName, lyrics)
        return if (lyrics.contains("[00:") || lyrics.contains("[01:")) {
            RawLyrics.Remote(synced = content)
        } else {
            RawLyrics.Remote(plain = content)
        }
    }

    private fun JsonObject.jsonLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: 0L

    companion object {
        private const val SEARCH_URL = "https://msearchcdn.kugou.com/api/v3/search/song"
        private const val DATA_URL = "https://wwwapi.kugou.com/yy/index.php"
        private const val LEGACY_BASE = "https://lyrics.kugou.com/"
    }
}
