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
import com.mardous.booming.data.remote.lyrics.api.binilyrics.BiniLyricsApi
import com.mardous.booming.data.remote.lyrics.api.lrclib.LrcLibApi
import com.mardous.booming.data.remote.lyrics.api.lyrically.LyricallyApi
import com.mardous.booming.data.remote.lyrics.api.paxsenix.PaxsenixNeteaseApi
import com.mardous.booming.data.remote.lyrics.api.synclrc.SyncLrcApi
import com.mardous.booming.data.remote.lyrics.api.youlyplus.YouLyPlusApi
import com.mardous.booming.extensions.media.albumArtistName
import com.mardous.booming.extensions.media.extractMainArtistName
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class LyricsProviderParams(
    val providers: List<LyricsProvider> = LyricsProvider.AvailableProviders,
    val ignoreWifiSetting: Boolean = false,
    val ignoreProviderSetting: Boolean = false,
    val preferWordSynced: Boolean = false
)

/**
 * A completed provider sweep. [sweptAll] is true only when every enabled
 * provider finished and genuinely had no lyrics - a timeout-truncated or
 * failed sweep must never be treated as a definitive miss.
 */
class SweepResult(
    val remote: RawLyrics.Remote,
    val sweptAll: Boolean
)

class LyricsDownloadService(client: HttpClient) {

    private val apiByProvider = mapOf(
        LyricsProvider.BiniLyrics to BiniLyricsApi(client),
        LyricsProvider.YouLyPlus to YouLyPlusApi(client),
        LyricsProvider.LRCLib to LrcLibApi(client),
        LyricsProvider.BetterLyrics to BetterLyricsApi(client),
        LyricsProvider.Lyrically to LyricallyApi(client),
        LyricsProvider.SyncLRC to SyncLrcApi(client),
        LyricsProvider.Netease to PaxsenixNeteaseApi(client)
    )

    private val lrcLyricsParser = LrcLyricsParser()
    private val ttmlLyricsParser = TtmlLyricsParser()

    @Throws(IOException::class)
    suspend fun remoteLyrics(
        song: Song,
        title: String = song.title,
        artist: String = song.albumArtistName(),
        providerParams: LyricsProviderParams = LyricsProviderParams()
    ): RawLyrics.Remote = remoteLyricsDetailed(song, title, artist, providerParams).remote

    /**
     * Runs the provider race and, when the album-artist query finds nothing,
     * retries once with the raw track artist - the same query the lyrics
     * editor uses. Album and track artists often differ (feat. tracks,
     * differing tags, online songs), which made the automatic search miss
     * lyrics the editor could find.
     */
    suspend fun remoteLyricsDetailed(
        song: Song,
        title: String = song.title,
        artist: String = song.albumArtistName(),
        providerParams: LyricsProviderParams = LyricsProviderParams()
    ): SweepResult {
        check(providerParams.providers.isNotEmpty()) { "No providers configured" }

        val offline = SweepResult(RawLyrics.Remote(), sweptAll = false)
        if (song == Song.emptySong || !NetworkFeature.isOnline(ignoreWifiSetting = providerParams.ignoreWifiSetting))
            return offline

        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = artist.extractMainArtistName()
        val providers = providerParams.providers.filter { provider ->
            provider.isAvailableForCurrentPolicy &&
                    (provider.isEnabled || providerParams.ignoreProviderSetting) &&
                    apiByProvider.containsKey(provider)
        }
        if (providers.isEmpty()) return offline

        val outcome = try {
            raceProviders(song, cleanedTitle, cleanedArtist, providers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Lyrics download failed with error:", e)
            return offline
        }

        if (!outcome.remote.hasSynced && !outcome.remote.hasPlain) {
            val fallbackArtist = song.artistName.extractMainArtistName()
            if (fallbackArtist.isNotBlank() && !fallbackArtist.equals(cleanedArtist, ignoreCase = true)) {
                val fallbackOutcome = try {
                    raceProviders(song, cleanedTitle, fallbackArtist, providers)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback lyrics request failed with error:", e)
                    null
                }
                return if (fallbackOutcome != null) {
                    SweepResult(
                        remote = outcome.remote.accept(fallbackOutcome.remote),
                        sweptAll = outcome.sweptAll && fallbackOutcome.sweptAll
                    )
                } else {
                    SweepResult(outcome.remote, sweptAll = false)
                }
            }
        }
        return SweepResult(outcome.remote, outcome.sweptAll)
    }

    /**
     * Queries every provider in parallel and merges results as they arrive,
     * so the pipeline latency is the fastest provider rather than the sum
     * of all of them. The quality ladder is strict: a word-by-word result
     * ends the race immediately; a line-synced result opens a short grace
     * window for a word-by-word provider to beat it before settling. The
     * whole race is capped at [RACE_TIMEOUT_MILLIS]; on timeout the partial
     * merge still wins.
     */
    private suspend fun raceProviders(
        song: Song,
        title: String,
        artist: String,
        providers: List<LyricsProvider>
    ): RaceOutcome {
        val outcome = RaceOutcome()
        val timedOut: Boolean = try {
            withTimeoutOrNull(RACE_TIMEOUT_MILLIS) {
                coroutineScope {
                    val pending = providers.map { provider ->
                        async(Dispatchers.IO) {
                            try {
                                apiByProvider.getValue(provider).downloadLyrics(song, title, artist)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Error during lyrics request (${provider.displayName})", e)
                                null
                            }
                        }
                    }.toMutableSet()

                    try {
                        var lineSyncedAt = 0L
                        while (pending.isNotEmpty()) {
                            val completed: Pair<Deferred<RawLyrics.Remote?>, RawLyrics.Remote?>?
                            if (lineSyncedAt == 0L) {
                                // No synced result yet - keep waiting for
                                // anything (word-by-word, line or plain).
                                completed = select {
                                    for (candidate in pending) {
                                        candidate.onAwait { response -> candidate to response }
                                    }
                                }
                            } else {
                                // A line-synced result is on the table; give
                                // word-by-word providers a short grace window.
                                val graceLeft = lineSyncedAt + LINE_SYNCED_GRACE_MILLIS -
                                        System.currentTimeMillis()
                                if (graceLeft <= 0) break
                                completed = withTimeoutOrNull(graceLeft) {
                                    select<Pair<Deferred<RawLyrics.Remote?>, RawLyrics.Remote?>> {
                                        for (candidate in pending) {
                                            candidate.onAwait { response -> candidate to response }
                                        }
                                    }
                                } ?: break
                            }
                            val (deferred, response) = completed ?: break
                            pending.remove(deferred)

                            if (response != null) {
                                val wordSynced = response.synced != null &&
                                        isWordSynced(song, response.synced.lyrics)
                                if (wordSynced && outcome.wordSyncedCandidate == null) {
                                    outcome.wordSyncedCandidate = response.synced
                                } else if (!wordSynced && lineSyncedAt == 0L && response.synced != null) {
                                    lineSyncedAt = System.currentTimeMillis()
                                }
                                outcome.remote = outcome.remote.accept(response)
                                if (wordSynced) break
                            }
                        }
                    } finally {
                        pending.forEach { it.cancel() }
                    }
                }
            } == null
        } catch (e: CancellationException) {
            throw e
        }

        // Word-by-word always wins over whichever synced content merged first.
        val wordSynced = outcome.wordSyncedCandidate
        if (wordSynced != null && wordSynced != outcome.remote.synced) {
            outcome.remote = outcome.remote.copy(synced = wordSynced)
        }
        outcome.sweptAll = !timedOut && !outcome.remote.hasSynced && !outcome.remote.hasPlain
        return outcome
    }

    private class RaceOutcome {
        var remote: RawLyrics.Remote = RawLyrics.Remote()
        var wordSyncedCandidate: RawLyrics.Remote.Content? = null
        var sweptAll: Boolean = false
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

        /**
         * Upper bound for the whole provider race. Per-provider request
         * timeouts are 5-15s, but providers like Lyrically chain several
         * sequential requests, so a global cap keeps the worst case sane.
         * With the word-by-word early exit this cap only binds when nothing
         * was found, where letting slow providers finish reduces false
         * "no lyrics" results.
         */
        private const val RACE_TIMEOUT_MILLIS = 20_000L

        /**
         * Once a line-synced result has landed, word-by-word providers get
         * this long to beat it before the race settles for the line-synced.
         */
        private const val LINE_SYNCED_GRACE_MILLIS = 3_500L

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