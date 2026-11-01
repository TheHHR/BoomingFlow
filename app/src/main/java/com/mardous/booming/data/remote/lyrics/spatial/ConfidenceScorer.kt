package com.mardous.booming.data.remote.lyrics.spatial

import android.util.Log
import java.util.Locale
import kotlin.math.abs

/**
 * Scores a [SearchHit] against its [TrackMetadata], ported from
 * SpatialFlow's ConfidenceScorer.
 *
 * confidence = titleMatch * 0.25 + artistMatch * 0.25 +
 *   durationMatch * 0.2 + syncQuality * 0.2 + providerTrust * 0.1
 *
 * Thresholds: >= 0.85 accept immediately, 0.6-0.85 show but keep
 * searching in the background, < 0.6 reject and try the next provider.
 */
class ConfidenceScorer {

    companion object {
        private const val TAG = "ConfidenceScorer"
        const val THRESHOLD_ACCEPT = 0.85f
        const val THRESHOLD_SHOW = 0.6f
    }

    fun score(result: SearchHit?, track: TrackMetadata?): Float {
        if (result == null || track == null) return 0f

        val titleScore = scoreTitleMatch(result, track)
        val artistScore = scoreArtistMatch(result, track)
        val durationScore = scoreDurationMatch(result, track)
        val syncScore = scoreSyncQuality(result)
        val providerScore = scoreProviderTrust(result.providerName)

        var confidence = (titleScore * 0.25f) + (artistScore * 0.25f) +
                (durationScore * 0.2f) + (syncScore * 0.2f) +
                (providerScore * 0.1f)

        if (result.synced != null && syncScore > 0.8f) {
            confidence = (confidence + 0.05f).coerceAtMost(1.0f)
        }

        if (result.instrumental) {
            confidence = confidence.coerceAtMost(0.4f)
        }

        Log.d(
            TAG, String.format(
                Locale.US,
                "Score: %.3f [title=%.2f, artist=%.2f, dur=%.2f, sync=%.2f, prov=%.2f] for %s",
                confidence, titleScore, artistScore, durationScore, syncScore,
                providerScore, result.providerName
            )
        )

        return confidence
    }

    private fun scoreTitleMatch(result: SearchHit, track: TrackMetadata): Float {
        val matchedTitle = result.matchedTitle
        if (matchedTitle.isNullOrEmpty()) {
            return if (result.hasLyrics()) 0.5f else 0f
        }
        return LyricsNormalizer.similarity(track.cleanedTitle, matchedTitle)
    }

    private fun scoreArtistMatch(result: SearchHit, track: TrackMetadata): Float {
        val matchedArtist = result.matchedArtist
        if (matchedArtist.isNullOrEmpty()) {
            return if (result.hasLyrics()) 0.5f else 0f
        }
        return LyricsNormalizer.similarity(track.cleanedArtist, matchedArtist)
    }

    private fun scoreDurationMatch(result: SearchHit, track: TrackMetadata): Float {
        val matchedDuration = result.matchedDuration
        if (matchedDuration <= 0 || track.durationMs <= 0) {
            return 0.5f
        }

        val trackDurationSec = track.durationMs / 1000f
        val diff = abs(trackDurationSec - matchedDuration)

        return when {
            diff <= 2 -> 1.0f
            diff <= 5 -> 0.9f
            diff <= 10 -> 0.7f
            diff <= 30 -> 0.4f
            else -> 0.1f
        }
    }

    private fun scoreSyncQuality(result: SearchHit): Float {
        val synced = result.synced ?: result.wordSynced
        if (!synced.isNullOrEmpty()) {
            val lineCount = countSyncedLines(synced)
            return when {
                lineCount >= 20 -> 1.0f
                lineCount >= 10 -> 0.8f
                lineCount >= 5 -> 0.6f
                lineCount > 0 -> 0.4f
                else -> 0.0f
            }
        }

        if (!result.plain.isNullOrEmpty()) {
            val lines = result.plain!!.lines().size
            return when {
                lines >= 10 -> 0.5f
                lines >= 5 -> 0.3f
                else -> 0.2f
            }
        }

        return 0f
    }

    private fun scoreProviderTrust(providerName: String?): Float {
        if (providerName?.startsWith("BetterLyrics") == true ||
            providerName?.contains("Portato", ignoreCase = true) == true ||
            providerName?.contains("Musixmatch", ignoreCase = true) == true ||
            providerName?.contains("Unison", ignoreCase = true) == true ||
            providerName?.contains("BiniLyrics", ignoreCase = true) == true
        ) {
            return 0.98f
        }
        return when (providerName) {
            "SyncLRC" -> 1.0f
            "LRCLib" -> 0.95f
            "Kouzu" -> 0.95f
            "YouLyPlus" -> 0.90f
            "YouTube Music" -> 0.85f
            "Netease" -> 0.85f
            "Apple Music" -> 0.92f
            "Spotify" -> 0.80f
            "YouTube Captions" -> 0.70f
            "KuGou" -> 0.80f
            "SimpMusic" -> 0.75f
            else -> 0.50f
        }
    }

    private fun countSyncedLines(lyrics: String): Int {
        if (lyrics.isEmpty()) return 0
        return lyrics.lines().count {
            it.matches("^\\[\\d{2}:\\d{2}\\.\\d{2,3}].*".toRegex()) ||
                    it.matches("^\\[\\d+,\\d+].*".toRegex())
        }
    }

    enum class Action {
        ACCEPT,
        SHOW_AND_CONTINUE,
        REJECT
    }
}
