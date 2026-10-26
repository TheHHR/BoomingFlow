package com.mardous.booming.data.remote.lyrics.api.paxsenix

import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * NetEase-sourced lyrics via the Paxsenix API. The word-by-word klyric
 * karaoke track is preferred when present; otherwise synced LRC or plain
 * text.
 */
class PaxsenixNeteaseApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.Netease

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        val query = "$title $artist"

        PaxsenixClient.request(client, "/netease/lyrics", listOf("q" to query))
            ?.let { raw -> toRemote(raw)?.let { return it } }

        val searchBody = PaxsenixClient.request(client, "/netease/search", listOf("q" to query))
            ?: return null
        val trackId = PaxsenixClient.extractFirstId(searchBody) ?: return null

        val lyricsBody = PaxsenixClient.request(
            client, "/netease/lyrics", listOf("id" to trackId, "word" to "true")
        ) ?: return null

        // Prefer the klyric word-by-word karaoke content when present.
        val klyric = runCatching {
            Json.parseToJsonElement(lyricsBody).jsonObject["klyric"]
                ?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (!klyric.isNullOrBlank()) {
            return RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, klyric))
        }
        return toRemote(lyricsBody)
    }

    private fun toRemote(raw: String): RawLyrics.Remote? {
        val lyrics = PaxsenixClient.extractLyrics(raw)?.takeIf { it.isNotBlank() } ?: return null
        return if (lyrics.contains("[00:") || lyrics.contains("[01:")) {
            RawLyrics.Remote(synced = RawLyrics.Remote.Content(provider.displayName, lyrics))
        } else {
            RawLyrics.Remote(plain = RawLyrics.Remote.Content(provider.displayName, lyrics))
        }
    }
}
