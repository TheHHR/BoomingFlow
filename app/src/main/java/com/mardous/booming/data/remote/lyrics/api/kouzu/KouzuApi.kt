package com.mardous.booming.data.remote.lyrics.api.kouzu

import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.RateLimiter
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.data.remote.lyrics.api.SyllableLrcConverter
import com.mardous.booming.util.Constants.SIMPLE_USER_AGENT
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.userAgent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kouzu MLC lyrics (mlc.kouzu.in). The search endpoint matches by title and
 * artist; the lyrics endpoint returns the shared syllable JSON that
 * [SyllableLrcConverter] turns into Enhanced LRC.
 */
class KouzuApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.Kouzu

    private val rateLimiter = RateLimiter("KouzuApi")

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        if (rateLimiter.isRateLimited) return null
        if (title.isBlank() || artist.isBlank()) return null

        val searchBody = kouzuGet("/search") {
            parameter("q", "$title $artist")
            parameter("limit", 3)
        } ?: return null

        val ids = runCatching {
            (Json.parseToJsonElement(searchBody) as? JsonArray)
                ?.mapNotNull { element ->
                    (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                }.orEmpty()
        }.getOrDefault(emptyList())

        for (id in ids) {
            val lyrics = kouzuGet("/lyrics/$id")
                ?.let { SyllableLrcConverter.convert(it) }
                ?.takeIf { it.isNotBlank() }
                ?: continue
            return RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, lyrics))
        }
        return null
    }

    private suspend fun kouzuGet(path: String, block: HttpRequestBuilder.() -> Unit = {}): String? {
        return try {
            val response = client.get(KOUZU_API_URL + path) {
                header(HttpHeaders.Accept, "application/json")
                userAgent(SIMPLE_USER_AGENT)
                timeout {
                    connectTimeoutMillis = 5000
                    socketTimeoutMillis = 10000
                    requestTimeoutMillis = 15000
                }
                block()
            }
            if (response.status == HttpStatusCode.OK) {
                response.bodyAsText().takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val KOUZU_API_URL = "https://mlc.kouzu.in/api"
    }
}
