package com.mardous.booming.data.remote.lyrics.model

import kotlinx.serialization.Serializable

@Serializable
data class UnisonResponse(
    val success: Boolean? = null,
    val data: UnisonLyricData? = null
)

@Serializable
data class UnisonLyricData(
    val lyrics: String? = null,
    val format: String? = null,
    val syncType: String? = null
)

@Serializable
data class SimpMusicResponse(
    val data: List<SimpMusicLyricItem>? = null
)

@Serializable
data class SimpMusicLyricItem(
    val songTitle: String? = null,
    val artistName: String? = null,
    val plainLyric: String? = null,
    val syncedLyrics: String? = null
)
