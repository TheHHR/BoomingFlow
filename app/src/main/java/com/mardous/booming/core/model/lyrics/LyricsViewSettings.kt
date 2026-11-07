package com.mardous.booming.core.model.lyrics

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle

@Immutable
class LyricsViewSettings(
    val isCenterCurrentLine: Boolean,
    val isCenterHorizontally: Boolean,
    val enableSyllableLyrics: Boolean,
    val enableKaraokeStyle: Boolean,
    val progressiveColoring: Boolean,
    val blurEffect: Boolean,
    val shadowEffect: Boolean,
    val showTranslation: Boolean,
    val showTransliteration: Boolean,
    val resumeOnSeek: Boolean,
    val syncedStyle: TextStyle,
    val unsyncedStyle: TextStyle,
    val lineSpacing: Int
) {

    interface Key {
        companion object {
            const val ENABLE_SYLLABLE_LYRICS = "enable_syllable_lyrics"
            const val ENABLE_KARAOKE_STYLE = "enable_karaoke_style"
            const val USE_CUSTOM_FONT = "lyrics_use_custom_font"
            const val SELECTED_CUSTOM_FONT = "lyrics_custom_font"
            const val CENTER_CURRENT_LINE = "lyrics_center_current_line"
            const val CENTER_HORIZONTALLY = "lyrics_center_horizontally"
            const val LINE_SPACING = "lyrics_line_spacing"
            const val PROGRESSIVE_COLORING = "lyrics_progressive_coloring"
            const val SHOW_TRANSLATION = "lyrics_show_translation"
            const val SHOW_TRANSLITERATION = "lyrics_show_transliteration"
            const val RESUME_ON_SEEK = "lyrics_resume_on_seek"
            const val BLUR_EFFECT = "lyrics_text_blur"
            const val SHADOW_EFFECT = "lyrics_text_shadow"
            const val SYNCED_BOLD_FONT = "synced_lyrics_bold_font"
            const val UNSYNCED_BOLD_FONT = "unsynced_lyrics_bold_font"
            const val SYNCED_FONT_SIZE = "synced_lyrics_font_size_player"
            const val UNSYNCED_FONT_SIZE = "unsynced_lyrics_font_size_player"
        }
    }
}
