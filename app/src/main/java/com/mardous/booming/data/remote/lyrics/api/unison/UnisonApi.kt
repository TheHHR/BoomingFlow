package com.mardous.booming.data.remote.lyrics.api.unison

import android.content.Context
import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.data.remote.lyrics.spatial.security.DeviceKeyIdentityManager
import com.mardous.booming.util.Constants.SIMPLE_USER_AGENT
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unison community rich-synced and syllable-synced lyrics, ported from
 * SpatialFlow's UnisonLyricsProvider. Requests carry the per-device
 * cryptographic keyId as the `x-key-id` header.
 *
 * Endpoint: GET https://unison.betterlyrics.org/lyrics?v=&song=&artist=&duration=&album=
 */
class UnisonApi(
    private val context: Context,
    private val client: HttpClient
) : LyricsApi {

    override val provider = LyricsProvider.Unison

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        val keyId = runCatching { DeviceKeyIdentityManager.getKeyId(context) }
            .getOrNull()

        for ((qArtist, qTitle) in listOf(artist to title)) {
            if (qArtist.isBlank() || qTitle.isBlank()) continue
            try {
                val response = client.get("$BASE_URL/lyrics") {
                    userAgent(SIMPLE_USER_AGENT)
                    header(HttpHeaders.Accept, "application/json, text/plain, */*")
                    parameter("v", onlineVideoId(song) ?: "")
                    parameter("song", qTitle)
                    parameter("artist", qArtist)
                    parameter("duration", (song.duration / 1000).toInt().coerceAtLeast(0))
                    val album = song.albumName.takeIf { it.isNotBlank() }
                    if (album != null) parameter("album", album)
                    if (!keyId.isNullOrBlank()) header("x-key-id", keyId)
                    timeout {
                        connectTimeoutMillis = 5000
                        socketTimeoutMillis = 10000
                        requestTimeoutMillis = 15000
                    }
                }
                if (response.status != HttpStatusCode.OK) continue

                val data = runCatching {
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonObject
                }.getOrNull() ?: continue
                val lyricsText = data["lyrics"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: continue

                val format = data["format"]?.jsonPrimitive?.contentOrNull
                    ?.lowercase() ?: "ttml"
                val syncType = data["syncType"]?.jsonPrimitive?.contentOrNull
                    ?.lowercase() ?: "richsync"
                val matchedSong = data["song"]?.jsonPrimitive?.contentOrNull ?: qTitle
                val matchedArtist = data["artist"]?.jsonPrimitive?.contentOrNull ?: qArtist

                return when (format) {
                    "plain" -> RawLyrics.Remote(
                        plain = RawLyrics.Remote.Content(provider.displayName, lyricsText)
                    )
                    else -> RawLyrics.Remote(
                        synced = RawLyrics.Remote.Content(provider.displayName, lyricsText)
                    )
                }.also {
                    Log.d(TAG, "Unison $format/$syncType lyrics for $matchedSong - $matchedArtist")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the next query.
            }
        }
        return null
    }

    private fun onlineVideoId(song: Song): String? {
        val uri = song.uri
        uri?.host?.takeIf { it.isNotBlank() }?.let { return it }
        return song.data.removePrefix("yt:")
            .takeIf { song.data.startsWith("yt:") && it.isNotBlank() }
    }

    companion object {
        private const val TAG = "UnisonApi"
        private const val BASE_URL = "https://unison.betterlyrics.org"
    }
}
