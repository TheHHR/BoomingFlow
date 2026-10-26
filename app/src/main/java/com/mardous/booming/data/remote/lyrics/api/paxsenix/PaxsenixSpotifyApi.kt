package com.mardous.booming.data.remote.lyrics.api.paxsenix

import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import io.ktor.client.HttpClient

/**
 * Spotify-sourced lyrics via the Paxsenix API. Returns synced LRC or plain
 * text.
 */
class PaxsenixSpotifyApi(private val client: HttpClient) : LyricsApi {

    override val provider = LyricsProvider.Spotify

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        val query = "$title $artist"

        PaxsenixClient.request(client, "/spotify/lyrics", listOf("q" to query))
            ?.let { raw -> toRemote(raw)?.let { return it } }

        val searchBody = PaxsenixClient.request(client, "/spotify/search", listOf("q" to query))
            ?: return null
        val trackId = PaxsenixClient.extractFirstId(searchBody) ?: return null

        val lyricsBody = PaxsenixClient.request(client, "/spotify/lyrics", listOf("id" to trackId))
            ?: return null
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
