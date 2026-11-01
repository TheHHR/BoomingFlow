package com.mardous.booming.data.remote.lyrics.spatial

/**
 * Normalized track metadata consumed by every lyrics provider and the
 * confidence scorer, ported from SpatialFlow.
 */
data class TrackMetadata(
    val rawTitle: String = "",
    val rawArtist: String = "",
    val cleanedTitle: String = "",
    val cleanedArtist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val filePath: String = "",
    val videoId: String? = null,
    val version: String = "original",
    val detectedLanguage: String = "unknown"
) {
    override fun toString(): String {
        return "TrackMetadata(title='$cleanedTitle', artist='$cleanedArtist', version='$version', lang='$detectedLanguage', duration=$durationMs)"
    }
}
