/*
 * Copyright (c) 2026 TheHHR
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

package com.mardous.booming.core.model.theme

import androidx.annotation.ColorInt
import com.kyant.m3color.hct.Hct
import com.kyant.m3color.scheme.SchemeNeutral
import com.kyant.m3color.scheme.SchemeTonalSpot

/**
 * Full Material You tonal schemes derived from the current song's accent
 * color ("Follow player color"). The Compose theme and the View-layer tint
 * targets both source their roles from here, so every surface that follows
 * the player stays in sync.
 */
object AccentSchemes {

    /**
     * Covers below this chroma read as grayscale; "Follow player color"
     * reverts to the base Material You scheme for them.
     */
    private const val MIN_USABLE_CHROMA = 15.0

    /**
     * The neutral gray accent used while playing grayscale or coverless
     * tracks. Fed through the neutral scheme, it recolors the app in a
     * clean gray instead of reverting to Material You.
     */
    val NEUTRAL_ACCENT: Int = 0xFF9E9E9E.toInt()

    /**
     * @return true when [accent] is a real, colorful accent (not 0, and not
     * a near-gray extraction result from a monochrome cover).
     */
    fun isColorful(@ColorInt accent: Int): Boolean =
        accent != 0 && runCatching {
            Hct.fromInt(accent).chroma >= MIN_USABLE_CHROMA
        }.getOrDefault(false)

    /** View-layer tonal roles as raw ARGB ints. */
    data class Scheme(
        @ColorInt val primary: Int,
        @ColorInt val onPrimary: Int,
        @ColorInt val primaryContainer: Int,
        @ColorInt val onPrimaryContainer: Int,
        @ColorInt val secondaryContainer: Int,
        @ColorInt val onSecondaryContainer: Int,
        @ColorInt val tertiaryContainer: Int,
        @ColorInt val onTertiaryContainer: Int,
        @ColorInt val surfaceTint: Int,
        @ColorInt val inversePrimary: Int,
        @ColorInt val outline: Int,
        @ColorInt val onSurfaceVariant: Int,
        @ColorInt val background: Int
    )

    fun viewScheme(@ColorInt accent: Int, isDark: Boolean): Scheme {
        val hct = Hct.fromInt(accent)
        // Colorful accents use the tonal-spot scheme; near-neutral accents
        // (the gray fallback) use the neutral scheme for a truly gray result.
        val scheme = if (hct.chroma >= MIN_USABLE_CHROMA) {
            SchemeTonalSpot(hct, isDark, 0.0)
        } else {
            SchemeNeutral(hct, isDark, 0.0)
        }
        return Scheme(
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
            secondaryContainer = scheme.secondaryContainer,
            onSecondaryContainer = scheme.onSecondaryContainer,
            tertiaryContainer = scheme.tertiaryContainer,
            onTertiaryContainer = scheme.onTertiaryContainer,
            surfaceTint = scheme.surfaceTint,
            inversePrimary = scheme.inversePrimary,
            outline = scheme.outline,
            onSurfaceVariant = scheme.onSurfaceVariant,
            background = scheme.background
        )
    }
}
