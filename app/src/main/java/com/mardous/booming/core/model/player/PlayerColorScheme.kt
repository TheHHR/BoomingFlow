/*
 * Copyright (c) 2025 Christians Martínez Alvarado
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

package com.mardous.booming.core.model.player

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.google.android.material.color.DynamicColors
import com.kyant.m3color.hct.Hct
import com.kyant.m3color.scheme.SchemeContent
import androidx.compose.ui.graphics.toArgb
import com.mardous.booming.R
import com.mardous.booming.core.model.PaletteColor
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.extensions.resources.onSurfaceColor
import com.mardous.booming.extensions.resources.primaryColor
import com.mardous.booming.extensions.resources.surfaceColor
import com.mardous.booming.extensions.resources.withAlpha
import com.mardous.booming.extensions.systemContrast
import com.mardous.booming.ui.component.compose.color.onThis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.material.R as M3R

typealias PlayerColorSchemeMode = PlayerColorScheme.Mode

typealias PlayerColorSchemeList = List<PlayerColorSchemeMode>

/**
 * Represents a cohesive set of UI colors tailored for the audio player interface.
 *
 * The color scheme is responsible for harmonizing media-derived colors (e.g. from album art)
 * with the app's current theme to achieve a visually pleasing and accessible appearance.
 * It ensures legibility, proper contrast, and consistency with Material Design principles.
 *
 * @property surfaceColor The background color used for player surfaces.
 * @property primaryColor A visually prominent color used for highlights and accents.
 * @property onSurfaceColor Main color used for foreground content.
 * @property onSurfaceVariantColor Subtle color for less prominent foreground content.
 *
 * @author Christians Martínez Alvarado (mardous)
 */
@Immutable
data class PlayerColorScheme(
    val mode: Mode,
    val appThemeToken: AppThemeToken,
    val blurToken: BlurToken,
    @param:ColorInt val surfaceColor: Int,
    @param:ColorInt val surfaceContainerColor: Int,
    @param:ColorInt val primaryColor: Int,
    @param:ColorInt val secondaryContainerColor: Int,
    @param:ColorInt val onSurfaceColor: Int,
    @param:ColorInt val onSurfaceVariantColor: Int,
    val spatialToken: SpatialToken = SpatialToken.None
) {

    val primary = androidx.compose.ui.graphics.Color(primaryColor)
    val onPrimary = androidx.compose.ui.graphics.Color(primaryColor).onThis()
    val surface = androidx.compose.ui.graphics.Color(surfaceColor)
    val onSurface = androidx.compose.ui.graphics.Color(onSurfaceColor)
    val onSurfaceVariant = androidx.compose.ui.graphics.Color(onSurfaceVariantColor)

    enum class Mode(
        @param:StringRes val titleRes: Int,
        @param:StringRes val descriptionRes: Int,
        val preferredAnimDuration: Long = 500
    ) {
        AppTheme(
            R.string.player_color_mode_app_theme_title,
            R.string.player_color_mode_app_theme_description
        ),
        MaterialYou(
            R.string.player_color_mode_material_you_title,
            R.string.player_color_mode_material_you_description,
            preferredAnimDuration = 1000
        ),
        Spatial(
            R.string.player_color_mode_spatial_title,
            R.string.player_color_mode_spatial_description,
            preferredAnimDuration = 1000
        ),
        Blur(
            R.string.player_color_mode_blur_title,
            R.string.player_color_mode_blur_description
        )
    }

    class BlurToken(val isBlur: Boolean, val blurRadius: Float) {
        companion object {
            val None = BlurToken(isBlur = false, blurRadius = 0f)
        }
    }

    class SpatialToken(val isSpatial: Boolean) {
        companion object {
            val None = SpatialToken(isSpatial = false)
        }
    }

    class AppThemeToken(
        private val isDark: Boolean,
        private val isDynamic: Boolean,
        private val accent: Int = 0,
        private val followEnabled: Boolean = false
    ) {
        /**
         * True when the scheme this token was stamped on no longer matches
         * the environment: night mode or dynamic-color availability
         * flipped, the follow-player-color toggle flipped, or (with
         * follow on) the song's accent changed. Without the accent in
         * the token, AppTheme schemes were never regenerated on song
         * change and the player kept stale colors until an app reload.
         */
        fun isStale(context: Context, currentAccent: Int, followNow: Boolean): Boolean {
            if (context.isNightMode != isDark) return true
            if (DynamicColors.isDynamicColorAvailable() != isDynamic) return true
            if (followNow != followEnabled) return true
            if (followNow && currentAccent != accent) return true
            return false
        }

        companion object {
            val None = AppThemeToken(isDark = false, isDynamic = false)
        }
    }

        companion object {
        val Unspecified = PlayerColorScheme(
            mode = Mode.AppTheme,
            appThemeToken = AppThemeToken.None,
            blurToken = BlurToken.None,
            surfaceColor = Color.TRANSPARENT,
            surfaceContainerColor = Color.TRANSPARENT,
            primaryColor = Color.TRANSPARENT,
            secondaryContainerColor = Color.TRANSPARENT,
            onSurfaceColor = Color.TRANSPARENT,
            onSurfaceVariantColor = Color.TRANSPARENT
        )

        /**
         * AppTheme mode under Follow player color: keep the app theme's
         * surfaces and text, but tint primary + secondary container from
         * the song's accent — the same derivation the app scheme uses
         * (TonalSpot family when colorful, Neutral when not). Mirrors
         * `BoomingMusicTheme`'s follow-player-color branch.
         */
        private fun appThemeFollowColorScheme(
            context: Context,
            color: PaletteColor
        ): PlayerColorScheme {
            val base = themeColorScheme(context)
            val accent = color.primaryColor
            val follow = com.mardous.booming.util.Preferences.followPlayerColor
            if (!follow || accent == 0) {
                // Stamp the follow-off state so toggling the preference
                // later invalidates this scheme and regenerates it.
                return base.copy(
                    appThemeToken = AppThemeToken(
                        isDark = context.isNightMode,
                        isDynamic = DynamicColors.isDynamicColorAvailable(),
                        accent = 0,
                        followEnabled = false
                    )
                )
            }
            val scheme = com.mardous.booming.ui.theme.schemeFromAccent(
                accent = accent,
                darkTheme = context.isNightMode,
                contrastLevel = context.systemContrast.toDouble()
            )
            return base.copy(
                primaryColor = scheme.primary.toArgb(),
                secondaryContainerColor = scheme.secondaryContainer.toArgb(),
                appThemeToken = AppThemeToken(
                    isDark = context.isNightMode,
                    isDynamic = DynamicColors.isDynamicColorAvailable(),
                    accent = accent,
                    followEnabled = true
                )
            )
        }

        /**
         * Returns a default color scheme based on the current app theme.
         *
         * It retrieves standard theme attributes such as primary color, text color, etc.
         *
         * @param context Context used to resolve theme attributes.
         * @return A [PlayerColorScheme] derived from theme defaults.
         */
        fun themeColorScheme(context: Context): PlayerColorScheme {
            val onSurfaceColor = context.onSurfaceColor()
            val onSurfaceVariantColor = onSurfaceColor.withAlpha(0.6f)
            return PlayerColorScheme(
                mode = Mode.AppTheme,
                appThemeToken = AppThemeToken(
                    isDark = context.isNightMode,
                    isDynamic = DynamicColors.isDynamicColorAvailable()
                ),
                blurToken = BlurToken.None,
                surfaceColor = context.surfaceColor(),
                surfaceContainerColor = context.resolveColor(M3R.attr.colorSurfaceContainerHigh),
                primaryColor = context.primaryColor(),
                secondaryContainerColor = context.resolveColor(M3R.attr.colorSecondaryContainer),
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariantColor = onSurfaceVariantColor
            )
        }

        /**
         * Creates a color scheme using the raw colors extracted from media (album art, etc).
         *
         * @param color A [PaletteColor] with extracted media colors.
         * @return A raw [PlayerColorScheme] using unmodified colors.
         */
        private suspend fun dynamicColorScheme(
            baseContext: Context,
            seedColor: Int
        ) = withContext(Dispatchers.IO) {
            val sourceHct = Hct.fromInt(seedColor)
            val colorScheme = SchemeContent(
                sourceHct,
                baseContext.isNightMode,
                baseContext.systemContrast.toDouble()
            )
            PlayerColorScheme(
                mode = Mode.MaterialYou,
                appThemeToken = AppThemeToken.None,
                blurToken = BlurToken.None,
                surfaceColor = colorScheme.surface,
                surfaceContainerColor = colorScheme.surfaceContainerHigh,
                primaryColor = colorScheme.primary,
                secondaryContainerColor = colorScheme.secondaryContainer,
                onSurfaceColor = colorScheme.onSurface,
                onSurfaceVariantColor = colorScheme.onSurfaceVariant.withAlpha(0.7f)
            )
        }

        private suspend fun blurColorScheme(
            context: Context,
            color: PaletteColor
        ) : PlayerColorScheme {
            // Blur intensity is locked (no user slider).
            // Seeded from the cover's accent (not the dominant background
            // swatch) so every player mode shares the same hue family as
            // the app chrome; the blurred backdrop image itself is still
            // the cover.
            return dynamicColorScheme(context, color.primaryColor).copy(
                mode = PlayerColorSchemeMode.Blur,
                blurToken = BlurToken(
                    isBlur = true,
                    blurRadius = 5f
                )
            )
        }

        /**
         * Spatial mode: Material You surfaces/text seeded from the cover's
         * accent (so content stays readable), with the fluid shader drawing
         * the album's vibrant/dark swatches behind it.
         */
        private suspend fun spatialColorScheme(
            context: Context,
            color: PaletteColor
        ): PlayerColorScheme {
            return dynamicColorScheme(context, color.primaryColor).copy(
                mode = PlayerColorSchemeMode.Spatial,
                spatialToken = SpatialToken(isSpatial = true)
            )
        }

        suspend fun autoColorScheme(
            context: Context,
            color: PaletteColor,
            mode: PlayerColorSchemeMode
        ): PlayerColorScheme {
            val colorScheme = when (mode) {
                Mode.AppTheme -> appThemeFollowColorScheme(context, color)
                Mode.MaterialYou -> dynamicColorScheme(context, color.primaryColor)
                Mode.Spatial -> spatialColorScheme(context, color)
                Mode.Blur -> blurColorScheme(context, color)
            }
            check(mode == colorScheme.mode)
            return colorScheme
        }
    }
}