package com.mardous.booming.data.remote.lyrics.api.kugou

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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import kotlin.math.abs

/**
 * KuGou lyrics, ported from SpatialFlow's KugouProvider. New suggestion
 * API first (works outside China), legacy candidates+download API as the
 * fallback. The best candidate is picked by closest duration; the
 * legacy download payload is Base64-encoded and decoded here.
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
        fetchViaNewApi(song, title, artist)?.let { return it }
        return fetchViaLegacyApi(song, title, artist)
    }

    private suspend fun fetchViaNewApi(song: Song, title: String, artist: String): RawLyrics.Remote? {
        return try {
            val query = "$title $artist"
            val response = client.get("https://msearchcdn.kugou.com/api/v3/search/song") {
                userAgent(SIMPLE_USER_AGENT)
                parameter("keyword", query)
                parameter("plat", 0)
                parameter("pagesize", 5)
                parameter("version", 9108)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (rateLimiter.handleResponse(response)) return null
            if (response.status != HttpStatusCode.OK) return null

            val bodyObj = runCatching {
                Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }.getOrNull() ?: return null
            if (bodyObj["status"]?.jsonPrimitive?.intOrNull != 1) return null

            val infoArray = bodyObj["data"]?.jsonObject?.get("info")?.jsonArray
                ?.takeIf { it.isNotEmpty() } ?: return null

            val trackDurationS = song.duration / 1000
            var best = infoArray.first().jsonObject
            var minDiff = Long.MAX_VALUE
            for (element in infoArray) {
                val cand = element.jsonObject
                val candDuration = cand["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                val diff = abs(candDuration - trackDurationS)
                if (diff < minDiff) {
                    minDiff = diff
                    best = cand
                }
            }

            val hash = best["hash"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null
            val albumId = best["album_id"]?.jsonPrimitive?.contentOrNull ?: ""

            val dataResponse = client.get("https://wwwapi.kugou.com/yy/index.php") {
                userAgent(SIMPLE_USER_AGENT)
                parameter("r", "play/getdata")
                parameter("hash", hash)
                parameter("album_id", albumId)
                parameter("mid", "1")
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (dataResponse.status != HttpStatusCode.OK) return null
            val dataObj = runCatching {
                Json.parseToJsonElement(dataResponse.bodyAsText()).jsonObject
            }.getOrNull() ?: return null
            if (dataObj["status"]?.jsonPrimitive?.intOrNull != 1) return null

            val lyricsStr = dataObj["data"]?.jsonObject
                ?.get("lyrics")?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.isNotBlank() } ?: return null
            toRemote(
                lyricsStr,
                matchedTitle = best["songname"]?.jsonPrimitive?.contentOrNull ?: title,
                matchedArtist = best["singername"]?.jsonPrimitive?.contentOrNull ?: artist
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchViaLegacyApi(song: Song, title: String, artist: String): RawLyrics.Remote? {
        return try {
            val query = "$title $artist"
            val response = client.get("$LEGACY_BASE/search") {
                userAgent(SIMPLE_USER_AGENT)
                parameter("ver", 1)
                parameter("man", "yes")
                parameter("client", "pc")
                parameter("keyword", query)
                parameter("duration", song.duration)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
            }
            if (response.status != HttpStatusCode.OK) return null
            val searchObj = runCatching {
                Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }.getOrNull() ?: return null
            val candidates = searchObj["candidates"]?.jsonArray
                ?.takeIf { it.isNotEmpty() } ?: return null

            var best = candidates.first().jsonObject
            var minDiff = Long.MAX_VALUE
            for (element in candidates) {
                val cand = element.jsonObject
                val candDuration = cand["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                val diff = abs(candDuration - song.duration)
                if (diff < minDiff) {
                    minDiff = diff
                    best = cand
                }
            }

            val id = best["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val accessKey = best["accesskey"]?.jsonPrimitive?.contentOrNull ?: return null

            val downloadResponse = client.get("$LEGACY_BASE/download") {
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
            val downloadObj = runCatching {
                Json.parseToJsonElement(downloadResponse.bodyAsText()).jsonObject
            }.getOrNull() ?: return null
            val base64Content = downloadObj["content"]?.jsonPrimitive?.contentOrNull
                ?: return null
            val lyricsStr = runCatching {
                String(Base64.getDecoder().decode(base64Content), Charsets.UTF_8).trim()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

            toRemote(
                lyricsStr,
                matchedTitle = best["song"]?.jsonPrimitive?.contentOrNull ?: title,
                matchedArtist = best["singer"]?.jsonPrimitive?.contentOrNull ?: artist
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun toRemote(
        lyricsStr: String,
        matchedTitle: String,
        matchedArtist: String
    ): RawLyrics.Remote {
        return if (lyricsStr.contains("[00:")) {
            RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, lyricsStr))
        } else {
            RawLyrics.Remote(
                plain = RawLyrics.Remote.Content(provider.displayName, lyricsStr)
            )
        }
    }

    companion object {
        private const val LEGACY_BASE = "http://lyrics.kugou.com"
    }
}
