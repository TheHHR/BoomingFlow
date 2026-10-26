package com.mardous.booming.data.remote.lyrics.api.synclrc

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
import io.ktor.client.request.userAgent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * SyncLRC (api.synclrc.dev). Can return word-by-word karaoke Enhanced LRC,
 * line-synced LRC or plain text; queries walk a ladder of fallbacks (karaoke
 * with full metadata, general query, then stripped title/artist) across two
 * mirrors.
 */
class SyncLrcApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.SyncLRC

    private val rateLimiter = RateLimiter("SyncLrcApi")

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        if (rateLimiter.isRateLimited) return null

        val durationSec = (song.duration / 1000).toString()
        val album = song.albumName.takeIf { it.isNotBlank() }
        val strippedTitle = title.replace(STRIPPED_PARTS_PATTERN, "").trim()
        val strippedArtist = artist.split(",", "&", "feat.", "Feat.", "ft.").first().trim()

        val queries = buildList {
            add(listOf("track" to title, "artist" to artist, "type" to "karaoke", "album" to album, "duration" to durationSec))
            add(listOf("track" to title, "artist" to artist, "album" to album, "duration" to durationSec))
            add(listOf("track" to title, "artist" to artist))
            if (strippedTitle.isNotEmpty() && (strippedTitle != title || strippedArtist != artist)) {
                add(listOf("track" to strippedTitle, "artist" to strippedArtist))
            }
        }

        for (base in SYNC_LRC_BASES) {
            for (query in queries) {
                try {
                    val response = client.get(base + "/lyrics") {
                        userAgent(SIMPLE_USER_AGENT)
                        for ((name, value) in query) {
                            value?.let { parameter(name, it) }
                        }
                        timeout {
                            connectTimeoutMillis = 5000
                            socketTimeoutMillis = 10000
                            requestTimeoutMillis = 15000
                        }
                    }
                    if (rateLimiter.handleResponse(response)) return null
                    if (response.status != HttpStatusCode.OK) continue

                    val root = runCatching {
                        Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                    }.getOrNull() ?: continue

                    toRemote(root)?.let { return it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
            }
        }
        return null
    }

    private fun toRemote(root: JsonObject): RawLyrics.Remote? {
        if (root["instrumental"]?.jsonPrimitive?.booleanOrNull == true) {
            return RawLyrics.Remote(instrumental = true)
        }
        val karaoke = root["karaoke"]?.jsonPrimitive?.contentOrNull
        val synced = root["synced"]?.jsonPrimitive?.contentOrNull
        val plain = root["plain"]?.jsonPrimitive?.contentOrNull

        val syncedContent = when {
            !karaoke.isNullOrBlank() && KARAOKE_WORD_PATTERN.containsMatchIn(karaoke) ->
                RawLyrics.Remote.Content(provider.displayName, karaoke)
            !synced.isNullOrBlank() ->
                RawLyrics.Remote.Content(provider.displayName, synced)
            else -> null
        }
        val plainContent = plain?.takeIf { it.isNotBlank() }
            ?.let { RawLyrics.Remote.Content(provider.displayName, it) }
        if (syncedContent == null && plainContent == null) return null

        return RawLyrics.Remote(plain = plainContent, synced = syncedContent)
    }

    companion object {
        private val SYNC_LRC_BASES = listOf("https://api.synclrc.dev", "https://synclrc.dev")

        /** Matches Enhanced LRC word-level timestamps like <00:27.60>. */
        private val KARAOKE_WORD_PATTERN = Regex("""<\d+:\d{2}[.:]\d+>""")
        private val STRIPPED_PARTS_PATTERN = Regex("""\s*\(.*?\)|\[.*?\]""")
    }
}
