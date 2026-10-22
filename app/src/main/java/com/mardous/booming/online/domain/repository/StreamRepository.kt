package com.mardous.booming.online.domain.repository

import com.mardous.booming.online.domain.error.DataError
import com.mardous.booming.online.domain.error.Result

interface StreamRepository {
    suspend fun getStreamUrl(
        videoId: String,
        forceHighQuality: Boolean = false,
        title: String? = null,
        artist: String? = null,
        isrc: String? = null,
        overridePreferOpus: Boolean? = null,
        isRetry: Boolean = false,
        durationMs: Long? = null
    ): Result<String, DataError.Network>
    suspend fun getPlaybackPosition(videoId: String): Result<Long, DataError.Network>
}
