package com.mardous.booming.core.model

import android.content.Context
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.extensions.resources.withAlpha
import com.mardous.booming.ui.component.views.PlaceholderDrawable

class PaletteColor(
    val backgroundColor: Int,
    val primaryColor: Int,
    val primaryTextColor: Int,
    val secondaryTextColor: Int,
    val spatialColors: SpatialSwatchColors = SpatialSwatchColors.Empty
) {
    companion object {
        fun errorColor(context: Context): PaletteColor {
            val backgroundColor = context.resolveColor(PlaceholderDrawable.BACKGROUND_COLOR)
            val foregroundColor = context.resolveColor(PlaceholderDrawable.FOREGROUND_COLOR)
            return PaletteColor(
                backgroundColor = backgroundColor,
                primaryColor = foregroundColor,
                primaryTextColor = foregroundColor,
                secondaryTextColor = foregroundColor.withAlpha(0.75f)
            )
        }
    }
}

/**
 * The three swatches driving the Spatial fluid background, extracted in the
 * same Palette pass as the rest of the song colors.
 */
data class SpatialSwatchColors(
    val vibrant: Int,
    val darkVibrant: Int,
    val darkMuted: Int
) {
    companion object {
        val Empty = SpatialSwatchColors(0, 0, 0)
    }
}