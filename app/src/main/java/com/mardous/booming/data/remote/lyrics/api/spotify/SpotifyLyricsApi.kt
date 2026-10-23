package com.mardous.booming.data.remote.lyrics.api.spotify

import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Fetches lyrics from Spotify via the paxsenix lyrics proxy
 * (https://lyrics.paxsenix.org). Same source as the Lyrically
 * provider, but backed by Spotify's catalog instead of Apple Music.
 */
class SpotifyLyricsApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.Spotify

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        val direct = runCatching {
            client.get("$BASE_URL/spotify/lyrics") {
                parameter("q", "$title $artist")
                header(HttpHeaders.UserAgent, "BoomingFlow")
            }.body<String>()
        }.getOrNull()
        contentFrom(direct)?.let { return it }

        val searchBody = runCatching {
            client.get("$BASE_URL/spotify/search") {
                parameter("q", "$title $artist")
                header(HttpHeaders.UserAgent, "BoomingFlow")
            }.body<String>()
        }.getOrNull() ?: return null

        val trackId = firstTrackId(searchBody) ?: return null

        val byId = runCatching {
            client.get("$BASE_URL/spotify/lyrics") {
                parameter("id", trackId)
                header(HttpHeaders.UserAgent, "BoomingFlow")
            }.body<String>()
        }.getOrNull()
        return contentFrom(byId)
    }

    private fun contentFrom(raw: String?): RawLyrics.Remote? {
        if (raw.isNullOrBlank() || raw.trim() == "null") return null
        val text = extractLyrics(raw) ?: return null
        return if (text.contains("[") && LRC_LINE.containsMatchIn(text)) {
            RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, text))
        } else {
            RawLyrics.Remote(plain = RawLyrics.Remote.Content(provider.displayName, text))
        }
    }

    private fun extractLyrics(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") && !trimmed.startsWith("[{")) {
            return trimmed
        }
        return try {
            val element = json.parseToJsonElement(trimmed)
            when {
                element is JsonObject -> {
                    val candidate = element.stringField("syncedLyrics")
                        ?: element.stringField("lyrics")
                        ?: element.stringField("lrc")
                        ?: element.stringField("content")
                    if (element.stringField("ok") == "false") null else candidate
                }
                element is JsonPrimitive && element.isString -> element.content.takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse lyrics response", e)
            trimmed.takeIf { it.isNotBlank() }
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun firstTrackId(searchBody: String): String? = try {
        val parsed = json.parseToJsonElement(searchBody).jsonObject
        val items = parsed.values.firstNotNullOfOrNull { element ->
            (element as? JsonObject)?.let { obj ->
                (obj["items"] as? JsonArray)
                    ?: ((obj["data"] as? JsonObject)?.get("items") as? JsonArray)
            }
        }
        val first = items?.firstOrNull() ?: return null
        val obj = (first as? JsonObject) ?: return null
        ((obj["data"] as? JsonObject) ?: obj).let { data ->
            ((data["uri"] ?: data["id"]) as? JsonPrimitive)?.content?.substringAfterLast(":")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse search response", e)
        null
    }

    companion object {
        private const val TAG = "SpotifyLyricsApi"
        private const val BASE_URL = "https://lyrics.paxsenix.org"
        private val LRC_LINE = Regex("""\[\d{2}:\d{2}[.:]\d{1,3}]""")
    }
}
