/*
 * Copyright (c) 2026 Christians Martínez Alvarado
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

package com.mardous.booming.data.remote.lyrics.api

import com.mardous.booming.data.model.network.NetworkFeature

enum class LyricsProvider(
    val displayName: String,
    private val networkFeature: NetworkFeature
) {
    Unison("Unison", NetworkFeature.Lyrics.Unison),
    BiniLyrics("BiniLyrics", NetworkFeature.Lyrics.BiniLyrics),
    YouLyPlus("YouLyPlus", NetworkFeature.Lyrics.YouLyPlus),
    Kouzu("Kouzu", NetworkFeature.Lyrics.Kouzu),
    SyncLRC("SyncLRC", NetworkFeature.Lyrics.SyncLRC),
    LRCLib("LRCLib", NetworkFeature.Lyrics.LRCLib),
    BetterLyrics("BetterLyrics", NetworkFeature.Lyrics.BetterLyrics),
    Lyrically("Lyrically", NetworkFeature.Lyrics.Lyrically),
    Spotify("Spotify", NetworkFeature.Lyrics.PaxsenixSpotify),
    YouTubeCaptions("YouTube Captions", NetworkFeature.Lyrics.PaxsenixYouTube),
    Netease("Netease", NetworkFeature.Lyrics.PaxsenixNetease),
    KuGou("KuGou", NetworkFeature.Lyrics.KuGou),
    SimpMusic("SimpMusic", NetworkFeature.Lyrics.SimpMusic);

    val isAvailableForCurrentPolicy get() = networkFeature.isAvailableForCurrentPolicy
    val isEnabled get() = networkFeature.isEnabled

    companion object {
        val AvailableProviders: List<LyricsProvider>
            get() = LyricsProvider.entries.filter { it.isAvailableForCurrentPolicy }
    }
}