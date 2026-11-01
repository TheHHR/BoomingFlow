package com.mardous.booming.data.remote.lyrics.spatial

import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SpatialFlow's parallel race over all providers, ported to the app's
 * [RawLyrics.Remote] slots with its behaviors intact:
 *
 *  - 12s global cap ([RACE_TIMEOUT_MILLIS]); the first word-by-word hit
 *    ends the race, the first line-synced hit opens a 4s grace window
 *    hoping for a word-by-word arrival, plain hits never stop the race.
 *  - Every finisher is scored by [ConfidenceScorer] against the repaired
 *    [TrackMetadata]; the running merge keeps the best hit per
 *    SpatialFlow's tier ordering (word-by-word beats line beats plain,
 *    then confidence).
 *  - [onProviderResult] fires per provider so the provider sheet can
 *    update rows live mid-race.
 */
class ProviderRouter(
    private val apis: Map<String, LyricsApi>,
    private val scorer: ConfidenceScorer
) {

    data class RaceOutcome(
        val merged: RawLyrics.Remote,
        val perProvider: Map<String, RawLyrics.Remote?>,
        val sweptAll: Boolean
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun raceAll(
        song: Song,
        title: String,
        artist: String,
        providers: List<LyricsProvider>,
        track: TrackMetadata,
        onProviderResult: (String, RawLyrics.Remote?) -> Unit = { _, _ -> }
    ): RaceOutcome {
        val perProvider = LinkedHashMap<String, RawLyrics.Remote?>()
        var merged = RawLyrics.Remote()
        var bestHit: SearchHit? = null

        val timedOut: Boolean = try {
            withTimeoutOrNull(RACE_TIMEOUT_MILLIS) {
                coroutineScope {
                    val pending = providers.mapNotNull { provider ->
                        val api = apis[provider.displayName] ?: return@mapNotNull null
                        async(Dispatchers.IO) {
                            try {
                                val remote = api.downloadLyrics(song, title, artist)
                                val hit = remote?.let { toHit(it, provider.displayName, track) }
                                provider.displayName to hit
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Lyrics request failed (${provider.displayName})", e)
                                provider.displayName to null
                            }
                        }
                    }.toMutableSet()

                    try {
                        var firstSyncedAtNanos: Long? = null
                        while (pending.isNotEmpty()) {
                            val graceRemainingNanos = firstSyncedAtNanos?.let { start ->
                                WORD_SYNCED_GRACE_NANOS - (System.nanoTime() - start)
                            }
                            if (graceRemainingNanos != null && graceRemainingNanos <= 0) break

                            val completed = select<Pair<Deferred<Pair<String, SearchHit?>>, Pair<String, SearchHit?>>?> {
                                for (candidate in pending) {
                                    candidate.onAwait { result -> candidate to result }
                                }
                                graceRemainingNanos?.let { remaining ->
                                    onTimeout(remaining / 1_000_000L + 1) { null }
                                }
                            } ?: break

                            val (deferred, result) = completed
                            pending.remove(deferred)
                            val (name, hit) = result
                            perProvider[name] = hit?.toRemote()
                            onProviderResult(name, hit?.toRemote())

                            if (hit != null && hit.hasLyrics()) {
                                bestHit = pickBetter(bestHit, hit)
                                val winner = bestHit!!.toRemote()
                                merged = merged.accept(
                                    winner.plain,
                                    winner.synced,
                                    winner.wordSynced
                                )
                                if (bestHit!!.isWordByWord) break
                                if (bestHit!!.isSynced && firstSyncedAtNanos == null) {
                                    firstSyncedAtNanos = System.nanoTime()
                                }
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

        val sweptAll = !timedOut && !merged.hasSynced &&
                !merged.hasWordSynced && !merged.hasPlain
        return RaceOutcome(merged, perProvider, sweptAll)
    }

    private fun toHit(remote: RawLyrics.Remote, name: String, track: TrackMetadata): SearchHit {
        val hit = SearchHit(
            plain = remote.plain?.lyrics,
            synced = remote.synced?.lyrics,
            wordSynced = remote.wordSynced?.lyrics,
            providerName = name,
            matchedTitle = track.cleanedTitle,
            matchedArtist = track.cleanedArtist,
            matchedDuration = track.durationMs / 1000f,
            instrumental = false
        )
        hit.confidence = scorer.score(hit, track)
        return hit
    }

    private fun pickBetter(current: SearchHit?, candidate: SearchHit): SearchHit {
        if (current == null) return candidate
        // Word-by-word always beats non-word-by-word.
        if (candidate.isWordByWord && !current.isWordByWord) return candidate
        if (!candidate.isWordByWord && current.isWordByWord) return current
        // Line-synced beats plain.
        if (candidate.isSynced && !current.isSynced) return candidate
        if (!candidate.isSynced && current.isSynced) {
            return if (current.confidence < 0.45f &&
                candidate.confidence > current.confidence + 0.2f
            ) candidate else current
        }
        // Same tier: higher confidence wins.
        return if (candidate.confidence > current.confidence) candidate else current
    }

    companion object {
        private const val TAG = "ProviderRouter"
        private const val RACE_TIMEOUT_MILLIS = 12_000L
        private const val WORD_SYNCED_GRACE_MILLIS = 4_000L
        private val WORD_SYNCED_GRACE_NANOS = WORD_SYNCED_GRACE_MILLIS * 1_000_000L
    }
}
