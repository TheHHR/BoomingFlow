package com.mardous.booming.data.remote.lyrics.api.ytmusic

import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.online.data.innertube.YouTubeMusic

/**
 * Official YouTube Music lyrics via InnerTube, ported from SpatialFlow's
 * YouTubeMusicLyricsProvider. Only applies to streaming songs that carry
 * a videoId; plain-text fallback source, so it sits last in priority.
 */
class YouTubeMusicLyricsApi : LyricsApi {

    override val provider = LyricsProvider.YouTubeMusic

    override suspend fun downloadLyrics(
        song: Song,
        title: String,
        artist: String
    ): RawLyrics.Remote? {
        val videoId = onlineVideoId(song)
        if (videoId.isNullOrBlank()) return null

        return try {
            val lyricsText = YouTubeMusic.lyrics(videoId).getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return null
            RawLyrics.Remote(
                plain = RawLyrics.Remote.Content(provider.displayName, lyricsText)
            )
        } catch (e: Exception) {
            Log.w(TAG, "YouTube Music lyrics fetch failed for $videoId: ${e.message}")
            null
        }
    }

    private fun onlineVideoId(song: Song): String? {
        val uri = song.uri
        uri?.host?.takeIf { it.isNotBlank() }?.let { return it }
        return song.data.removePrefix("yt:")
            .takeIf { song.data.startsWith("yt:") && it.isNotBlank() }
    }

    companion object {
        private const val TAG = "YTMusicLyricsApi"
    }
}
