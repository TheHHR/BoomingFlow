package com.mardous.booming.data.remote.lyrics.api.youlyplus

import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.data.remote.lyrics.api.SyllableLrcConverter
import com.mardous.booming.util.Constants.SIMPLE_USER_AGENT
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.userAgent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * YouLyPlus lyrics, queried across several mirrors. The v1 endpoint returns
 * word-by-word TTML; the v2 endpoint returns the shared syllable JSON that
 * [SyllableLrcConverter] turns into Enhanced LRC.
 */
class YouLyPlusApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.YouLyPlus

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        val durationSec = song.duration / 1000
        val album = song.albumName.takeIf { it.isNotBlank() }

        val ttml = fetchFromMirrors("v1/ttml/get", title, artist, album, durationSec) { body ->
            val trimmed = body.trim()
            if (trimmed.startsWith("<")) {
                trimmed
            } else {
                runCatching {
                    (Json.parseToJsonElement(body) as? JsonObject)
                        ?.get("ttml")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.startsWith("<") }
                }.getOrNull()
            }
        }
        if (!ttml.isNullOrBlank()) {
            return RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, ttml))
        }

        val lrc = fetchFromMirrors("v2/lyrics/get", title, artist, album, durationSec) { body ->
            SyllableLrcConverter.convert(body)
        }
        if (!lrc.isNullOrBlank()) {
            return RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, lrc))
        }
        return null
    }

    private suspend fun fetchFromMirrors(
        path: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        decode: (String) -> String?
    ): String? {
        for (baseUrl in MIRRORS) {
            try {
                val response = client.get(baseUrl + path) {
                    userAgent(SIMPLE_USER_AGENT)
                    parameter("title", title)
                    parameter("artist", artist)
                    album?.let { parameter("album", it) }
                    if (durationSec > 0) parameter("duration", durationSec)
                    timeout {
                        connectTimeoutMillis = 5000
                        socketTimeoutMillis = 10000
                        requestTimeoutMillis = 15000
                    }
                }
                if (response.status == HttpStatusCode.OK) {
                    val body = response.bodyAsText()
                    if (body.isNotBlank()) {
                        decode(body)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try the next mirror.
            }
        }
        return null
    }

    companion object {
        private val MIRRORS = listOf(
            "https://mlc.kouzu.in/",
            "https://lyricsplus.binimum.org/",
            "https://lyricsplus.prjktla.my.id/",
            "https://lyricsplus.prjktla.workers.dev/",
            "https://lyricsplus.atomix.one/",
            "https://lyricsplus-seven.vercel.app/"
        )
    }
}
