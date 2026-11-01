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

import android.content.Context
import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.data.remote.lyrics.api.betterlyrics.BetterLyricsApi
import com.mardous.booming.data.remote.lyrics.api.binilyrics.BiniLyricsApi
import com.mardous.booming.data.remote.lyrics.api.kouzu.KouzuApi
import com.mardous.booming.data.remote.lyrics.api.kugou.KugouApi
import com.mardous.booming.data.remote.lyrics.api.lrclib.LrcLibApi
import com.mardous.booming.data.remote.lyrics.api.lyrically.LyricallyApi
import com.mardous.booming.data.remote.lyrics.api.paxsenix.PaxsenixAppleMusicApi
import com.mardous.booming.data.remote.lyrics.api.paxsenix.PaxsenixNeteaseApi
import com.mardous.booming.data.remote.lyrics.api.paxsenix.PaxsenixSpotifyApi
import com.mardous.booming.data.remote.lyrics.api.paxsenix.PaxsenixYouTubeApi
import com.mardous.booming.data.remote.lyrics.api.simpmusic.SimpMusicApi
import com.mardous.booming.data.remote.lyrics.api.synclrc.SyncLrcApi
import com.mardous.booming.data.remote.lyrics.api.unified.MusixmatchApi
import com.mardous.booming.data.remote.lyrics.api.unified.PortatoApi
import com.mardous.booming.data.remote.lyrics.api.unified.UnifiedKugouApi
import com.mardous.booming.data.remote.lyrics.api.unified.UnifiedLyricsBundleClient
import com.mardous.booming.data.remote.lyrics.api.unified.UnifiedTtmlApi
import com.mardous.booming.data.remote.lyrics.api.unison.UnisonApi
import com.mardous.booming.data.remote.lyrics.api.youlyplus.YouLyPlusApi
import com.mardous.booming.data.remote.lyrics.api.ytmusic.YouTubeMusicLyricsApi
import com.mardous.booming.data.remote.lyrics.spatial.ConfidenceScorer
import com.mardous.booming.data.remote.lyrics.spatial.MetadataRepair
import com.mardous.booming.data.remote.lyrics.spatial.ProviderRouter
import com.mardous.booming.data.remote.lyrics.spatial.TrackMetadata
import com.mardous.booming.extensions.media.albumArtistName
import com.mardous.booming.extensions.media.extractMainArtistName
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

class LyricsProviderParams(
    val providers: List<LyricsProvider> = LyricsProvider.AvailableProviders,
    val ignoreWifiSetting: Boolean = false,
    val ignoreProviderSetting: Boolean = false
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

class LyricsDownloadService(
    private val context: Context,
    client: HttpClient
) {

    private val scorer = ConfidenceScorer()
    private val repair = MetadataRepair()

    private val unifiedBundleClient = UnifiedLyricsBundleClient(context, client)

    private val apis: Map<String, LyricsApi> = buildMap {
        fun put(api: LyricsApi) = put(api.provider.displayName, api)
        put(BiniLyricsApi(client))
        put(YouLyPlusApi(client))
        put(LrcLibApi(client))
        put(BetterLyricsApi(client))
        put(LyricallyApi(client))
        put(SyncLrcApi(client))
        put(PaxsenixNeteaseApi(client))
        put(PaxsenixAppleMusicApi(client))
        put(PaxsenixSpotifyApi(client))
        put(PaxsenixYouTubeApi(client))
        put(KouzuApi(client))
        put(KugouApi(client))
        put(SimpMusicApi(client))
        put(UnisonApi(context, client))
        put(PortatoApi(unifiedBundleClient))
        put(MusixmatchApi(unifiedBundleClient))
        put(UnifiedTtmlApi(unifiedBundleClient))
        put(UnifiedKugouApi(unifiedBundleClient))
        put(YouTubeMusicLyricsApi())
    }

    private val router = ProviderRouter(apis = apis, scorer = scorer)

    /**
     * Live per-provider results of the latest sweep, keyed by provider
     * display name. The provider sheet collects this to update rows
     * mid-race (searching -> result / no lyrics). Null means the provider
     * has not finished yet.
     */
    private val _liveResults = MutableStateFlow<Map<String, RawLyrics.Remote?>>(emptyMap())
    val liveResults: StateFlow<Map<String, RawLyrics.Remote?>> = _liveResults.asStateFlow()

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

        val providers = providerParams.providers.filter { provider ->
            provider.isAvailableForCurrentPolicy &&
                    (provider.isEnabled || providerParams.ignoreProviderSetting) &&
                    apis.containsKey(provider.displayName)
        }
        if (providers.isEmpty()) return offline

        // SpatialFlow metadata repair: clean title/artist, detect version
        // and language before any provider is queried.
        val track = repair.repair(
            rawTitle = title,
            rawArtist = artist,
            album = song.albumName,
            durationMs = song.duration,
            filePath = song.data,
            videoId = onlineVideoId(song)
        )
        val cleanedTitle = track.cleanedTitle
        val cleanedArtist = track.cleanedArtist.ifBlank { artist.extractMainArtistName() }

        _liveResults.value = emptyMap()
        val outcome = try {
            raceProviders(song, cleanedTitle, cleanedArtist, providers, track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Lyrics download failed with error:", e)
            return offline
        }

        if (!outcome.remote.hasSynced && !outcome.remote.hasWordSynced && !outcome.remote.hasPlain) {
            val fallbackArtist = song.artistName.extractMainArtistName()
            if (fallbackArtist.isNotBlank() && !fallbackArtist.equals(cleanedArtist, ignoreCase = true)) {
                val fallbackOutcome = try {
                    raceProviders(song, cleanedTitle, fallbackArtist, providers, track)
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

    private fun onlineVideoId(song: Song): String? {
        val uri = song.uri
        uri?.host?.takeIf { it.isNotBlank() }?.let { return it }
        return song.data.removePrefix("yt:")
            .takeIf { song.data.startsWith("yt:") && it.isNotBlank() }
    }

    /**
     * SpatialFlow's 12s parallel race with per-provider live results,
     * 4s word-sync grace window and confidence-scored merging. The
     * latency is the fastest provider rather than the sum of all of them.
     */
    private suspend fun raceProviders(
        song: Song,
        title: String,
        artist: String,
        providers: List<LyricsProvider>,
        track: TrackMetadata
    ): RaceOutcome {
        val result = router.raceAll(song, title, artist, providers, track) { name, remote ->
            _liveResults.value = _liveResults.value + (name to remote)
        }
        return RaceOutcome(remote = result.merged, sweptAll = result.sweptAll)
    }

    private class RaceOutcome(
        var remote: RawLyrics.Remote = RawLyrics.Remote(),
        var sweptAll: Boolean = false
    )

    companion object {
        private const val TAG = "LyricsDownloadService"
    }
}