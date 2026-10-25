/*
 * Copyright (c) 2024 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.data.remote.lyrics

import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.local.lyrics.LyricsInfo
import com.mardous.booming.data.local.lyrics.lrc.LrcLyricsParser
import com.mardous.booming.data.local.lyrics.ttml.TtmlLyricsParser
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.data.remote.lyrics.api.betterlyrics.BetterLyricsApi
import com.mardous.booming.data.remote.lyrics.api.lrclib.LrcLibApi
import com.mardous.booming.data.remote.lyrics.api.lyrically.LyricallyApi
import com.mardous.booming.extensions.media.albumArtistName
import com.mardous.booming.extensions.media.extractMainArtistName
import io.ktor.client.HttpClient
import java.io.IOException

class LyricsProviderParams(
    val providers: List<LyricsProvider> = LyricsProvider.AvailableProviders,
    val ignoreWifiSetting: Boolean = false,
    val ignoreProviderSetting: Boolean = false,
    val preferWordSynced: Boolean = false
)

class LyricsDownloadService(client: HttpClient) {

    private val apiByProvider = mapOf(
        LyricsProvider.Lyrically to LyricallyApi(client),
        LyricsProvider.BetterLyrics to BetterLyricsApi(client),
        LyricsProvider.LRCLib to LrcLibApi(client)
    )

    private val lrcLyricsParser = LrcLyricsParser()
    private val ttmlLyricsParser = TtmlLyricsParser()

    @Throws(IOException::class)
    suspend fun remoteLyrics(
        song: Song,
        title: String = song.title,
        artist: String = song.albumArtistName(),
        providerParams: LyricsProviderParams = LyricsProviderParams()
    ): RawLyrics.Remote {
        check(providerParams.providers.isNotEmpty()) { "No providers configured" }

        var result = RawLyrics.Remote()
        var wordSyncedCandidate: RawLyrics.Remote.Content? = null
        if (song == Song.emptySong || !NetworkFeature.isOnline(ignoreWifiSetting = providerParams.ignoreWifiSetting))
            return result

        try {
            val cleanedTitle = cleanTitle(title)
            val cleanedArtist = artist.extractMainArtistName()
            for (provider in providerParams.providers) {
                if (!provider.isAvailableForCurrentPolicy ||
                    (!provider.isEnabled && !providerParams.ignoreProviderSetting)) continue

                val api = apiByProvider.getValue(provider)
                val apiResult = runCatching { api.downloadLyrics(song, cleanedTitle, cleanedArtist) }
                if (apiResult.isFailure) {
                    Log.e(TAG, "Error during lyrics request", apiResult.exceptionOrNull())
                }

                val response = apiResult.getOrNull() ?: continue

                if (providerParams.preferWordSynced && wordSyncedCandidate == null) {
                    if (isWordSynced(song, response.synced?.lyrics)) {
                        wordSyncedCandidate = response.synced
                    }
                }

                result = result.accept(response)
                if (result.hasBoth &&
                    (!providerParams.preferWordSynced || wordSyncedCandidate != null)) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lyrics download failed with error:", e)
        }

        if (providerParams.preferWordSynced) {
            // A word-synced response wins over whichever synced content was
            // merged first from an earlier provider.
            val synced = wordSyncedCandidate ?: result.synced?.takeIf { isWordSynced(song, it.lyrics) }
            if (synced != null && synced != result.synced) {
                result = result.copy(synced = synced)
            }
        }

        return result
    }

    private fun isWordSynced(song: Song, lyrics: String?): Boolean {
        if (lyrics.isNullOrBlank()) return false
        for (parser in listOf(lrcLyricsParser, ttmlLyricsParser)) {
            val info = parser.getInfo(lyrics)
            if (info is LyricsInfo.Valid && info.actuallySynced) {
                val parsed = parser.parse(lyrics, song.duration, false) ?: continue
                return parsed.lines.any { it.isWordSynced }
            }
        }
        return false
    }

    /**
     * Taken from [Metrolist](https://github.com/MetrolistGroup/Metrolist).
     */
    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in TITLE_CLEANUP_PATTERNS) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim()
    }

    companion object {
        private const val TAG = "LyricsDownloadService"

        private const val KEYWORDS = "official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit"

        private val TITLE_CLEANUP_PATTERNS = listOf(
            Regex("""\s*\((?>[^)]*?(?:$KEYWORDS)[^)]*?)\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*\[(?>[^\]]*?(?:$KEYWORDS)[^\]]*?)\]""", RegexOption.IGNORE_CASE),
            Regex("""\s*【(?>[^】]*?)】"""),
            Regex("""\s*\|.*$"""),
            Regex("""\s*-\s*(?:official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*\((?>feat\.[^)]*?)\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*\((?>ft\.[^)]*?)\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*\((?>[^)]*?\d{4}[^)]*?)\)""", RegexOption.IGNORE_CASE),
        )
    }
}