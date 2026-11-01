package com.mardous.booming.data.remote.lyrics.spatial

import com.mardous.booming.data.model.lyrics.RawLyrics

/**
 * Internal search result from a single provider inside the SpatialFlow
 * engine, ported from `LyricsResult`. Carries the raw payload plus the
 * matched metadata used by [ConfidenceScorer], and converts into the
 * app's [RawLyrics.Remote] slots on the way out.
 */
data class SearchHit(
    var plain: String? = null,
    var synced: String? = null,
    var wordSynced: String? = null,
    var providerName: String? = null,
    var confidence: Float = 0f,
    var matchedTitle: String? = null,
    var matchedArtist: String? = null,
    var matchedDuration: Float = 0f,
    var instrumental: Boolean = false,
    var fetchTimestamp: Long = System.currentTimeMillis()
) {
    fun hasLyrics(): Boolean =
        !plain.isNullOrEmpty() || !synced.isNullOrEmpty() || !wordSynced.isNullOrEmpty()

    val isSynced: Boolean get() = !synced.isNullOrEmpty() || !wordSynced.isNullOrEmpty()
    val isWordByWord: Boolean get() = !wordSynced.isNullOrEmpty()

    /**
     * Splits the hit across the [RawLyrics.Remote] content slots so the
     * app's quality ladder (word > line > plain) applies unchanged.
     */
    fun toRemote(): RawLyrics.Remote {
        val source = providerName
        return RawLyrics.Remote(
            plain = plain?.takeIf { it.isNotBlank() }
                ?.let { RawLyrics.Remote.Content(source, it) },
            synced = synced?.takeIf { it.isNotBlank() }
                ?.let { RawLyrics.Remote.Content(source, it) },
            wordSynced = wordSynced?.takeIf { it.isNotBlank() }
                ?.let { RawLyrics.Remote.Content(source, it) },
            instrumental = instrumental
        )
    }
}
