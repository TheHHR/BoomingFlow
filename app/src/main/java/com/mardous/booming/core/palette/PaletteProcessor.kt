package com.mardous.booming.core.palette

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import androidx.core.graphics.ColorUtils
import com.mardous.booming.core.model.PaletteColor
import com.mardous.booming.extensions.resources.isColorLight
import com.mardous.booming.util.color.NotificationColorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

object PaletteProcessor {

    private const val POPULATION_FRACTION_FOR_MORE_VIBRANT = 1.0f
    private const val MIN_SATURATION_WHEN_DECIDING = 0.19f
    private const val MINIMUM_IMAGE_FRACTION = 0.002
    private const val POPULATION_FRACTION_FOR_DOMINANT = 0.01f
    private const val POPULATION_FRACTION_FOR_WHITE_OR_BLACK = 2.5f

    private const val BLACK_MAX_LIGHTNESS = 0.08f
    private const val WHITE_MIN_LIGHTNESS = 0.90f
    private const val RESIZE_BITMAP_AREA = 150 * 150

    private const val LIGHTNESS_TEXT_DIFFERENCE_LIGHT = 20
    private const val LIGHTNESS_TEXT_DIFFERENCE_DARK = -10

    private val mBlackWhiteFilter = Palette.Filter { _: Int, hsl: FloatArray ->
        !isWhiteOrBlack(hsl)
    }

    suspend fun getPaletteColor(
        context: Context,
        bitmap: Bitmap,
        colorAccuracy: Boolean = true
    ): PaletteColor = withContext(Dispatchers.Default) {
        try {
            if (bitmap.isRecycled) {
                return@withContext PaletteColor.errorColor(context)
            }

            val requestedSize = if (colorAccuracy) 128 else 32
            val workingBitmap =
                if (bitmap.width > requestedSize || bitmap.height > requestedSize)
                    bitmap.scale(requestedSize, requestedSize)
                else bitmap

            // SpatialFlow-style extraction: a richer swatch pool, a
            // population-weighted chromatic accent and HSL post-processing
            // with monochrome fallbacks.
            val palette = Palette.from(workingBitmap)
                .maximumColorCount(24)
                .resizeBitmapArea(RESIZE_BITMAP_AREA)
                .generate()

            val allSwatches = palette.swatches
            val totalPopulation = allSwatches.sumOf { it.population }.coerceAtLeast(1)

            val candidateSwatches = listOfNotNull(
                palette.vibrantSwatch, palette.darkVibrantSwatch, palette.lightVibrantSwatch,
                palette.mutedSwatch, palette.darkMutedSwatch, palette.lightMutedSwatch,
                palette.dominantSwatch
            )
            val bestAccentSwatch = candidateSwatches.maxByOrNull { swatch ->
                val sat = swatch.hsl[1]
                val pop = swatch.population.toFloat() / totalPopulation.toFloat()
                sat * (0.25f + pop)
            }

            val accentHsl = FloatArray(3)
            ColorUtils.colorToHSL(
                bestAccentSwatch?.rgb ?: palette.getVibrantColor(0xFF8338EC.toInt()),
                accentHsl
            )

            val backgroundSwatch = palette.dominantSwatch
            val bgHsl = FloatArray(3)
            ColorUtils.colorToHSL(
                backgroundSwatch?.rgb ?: palette.getDominantColor(0xFF0F0F0F.toInt()),
                bgHsl
            )

            // Monochrome detection: three heuristics combined.
            val bgRed = Color.red(backgroundSwatch?.rgb ?: 0xFF0F0F0F.toInt())
            val bgGreen = Color.green(backgroundSwatch?.rgb ?: 0xFF0F0F0F.toInt())
            val bgBlue = Color.blue(backgroundSwatch?.rgb ?: 0xFF0F0F0F.toInt())
            val maxChannelDelta = (maxOf(bgRed, bgGreen, bgBlue) - minOf(bgRed, bgGreen, bgBlue)) / 255f
            val colorfulPopulation = allSwatches.filter { it.hsl[1] >= 0.16f }.sumOf { it.population }
            val colorfulRatio = colorfulPopulation.toFloat() / totalPopulation.toFloat()
            val isMonochromatic = (
                colorfulRatio < 0.08f && bgHsl[1] < 0.14f && accentHsl[1] < 0.18f && maxChannelDelta < 0.09f
            ) || (bgHsl[1] < 0.06f && accentHsl[1] < 0.08f)

            // Accent post-processing: make it pop.
            val finalAccent = if (isMonochromatic) {
                accentHsl[0] = 0f; accentHsl[1] = 0f; accentHsl[2] = 1f
                ColorUtils.HSLToColor(accentHsl)
            } else {
                if (accentHsl[2] < 0.60f) accentHsl[2] = 0.65f
                if (accentHsl[1] < 0.30f) accentHsl[1] = 0.60f
                ColorUtils.HSLToColor(accentHsl)
            }

            // Background post-processing.
            val finalBackground = if (isMonochromatic) {
                bgHsl[0] = 0f; bgHsl[1] = 0f; bgHsl[2] = 0.30f
                ColorUtils.HSLToColor(bgHsl)
            } else {
                if (bgHsl[1] < 0.16f) {
                    // Borrow hue from the accent when the dominant is muddy.
                    bgHsl[0] = accentHsl[0]
                    bgHsl[1] = (accentHsl[1] * 0.36f).coerceIn(0.18f, 0.40f)
                } else {
                    bgHsl[1] = bgHsl[1].coerceIn(0.18f, 0.60f)
                }
                bgHsl[2] = bgHsl[2].coerceIn(0.22f, 0.48f)
                ColorUtils.HSLToColor(bgHsl)
            }

            val foregroundColor = if (finalBackground.isColorLight) {
                selectForegroundColorForSwatches(
                    palette.darkVibrantSwatch,
                    palette.vibrantSwatch,
                    palette.darkMutedSwatch,
                    palette.mutedSwatch,
                    palette.dominantSwatch,
                    Color.BLACK
                )
            } else {
                selectForegroundColorForSwatches(
                    palette.lightVibrantSwatch,
                    palette.vibrantSwatch,
                    palette.lightMutedSwatch,
                    palette.mutedSwatch,
                    palette.dominantSwatch,
                    Color.WHITE
                )
            }

            val foregroundColors = ensureColors(finalBackground, foregroundColor)

            if (workingBitmap !== bitmap) {
                workingBitmap.recycle()
            }

            PaletteColor(
                backgroundColor = finalBackground,
                primaryColor = finalAccent,
                primaryTextColor = foregroundColors.first,
                secondaryTextColor = foregroundColors.second
            )
        } catch (t: Throwable) {
            Log.e("PaletteProcessor", "Failed to extract palette colors from bitmap", t)
            PaletteColor.errorColor(context)
        }
    }

    private fun selectForegroundColorForSwatches(
        moreVibrant: Palette.Swatch?,
        vibrant: Palette.Swatch?,
        moreMutedSwatch: Palette.Swatch?,
        mutedSwatch: Palette.Swatch?,
        dominantSwatch: Palette.Swatch?,
        fallbackColor: Int
    ): Int {
        var coloredCandidate = selectVibrantCandidate(moreVibrant, vibrant)
        if (coloredCandidate == null) {
            coloredCandidate = selectMutedCandidate(mutedSwatch, moreMutedSwatch)
        }
        return if (dominantSwatch != null && coloredCandidate != null) {
            if (dominantSwatch === coloredCandidate) {
                coloredCandidate.rgb
            } else if ((coloredCandidate.population.toFloat() / dominantSwatch.population < POPULATION_FRACTION_FOR_DOMINANT) &&
                dominantSwatch.hsl[1] > MIN_SATURATION_WHEN_DECIDING
            ) {
                dominantSwatch.rgb
            } else {
                coloredCandidate.rgb
            }
        } else if (dominantSwatch != null && hasEnoughPopulation(dominantSwatch)) {
            dominantSwatch.rgb
        } else {
            fallbackColor
        }
    }

    private fun selectMutedCandidate(first: Palette.Swatch?, second: Palette.Swatch?): Palette.Swatch? {
        val firstValid = first != null && hasEnoughPopulation(first)
        val secondValid = second != null && hasEnoughPopulation(second)
        if (firstValid && secondValid) {
            val firstSaturation = first.hsl[1]
            val secondSaturation = second.hsl[1]
            val populationFraction = (first.population / second.population).toFloat()
            return if (firstSaturation * populationFraction > secondSaturation) first else second
        } else if (firstValid) {
            return first
        } else if (secondValid) {
            return second
        }
        return null
    }

    private fun selectVibrantCandidate(first: Palette.Swatch?, second: Palette.Swatch?): Palette.Swatch? {
        val firstValid = first != null && hasEnoughPopulation(first)
        val secondValid = second != null && hasEnoughPopulation(second)
        return if (firstValid && secondValid) {
            val firstPopulation = first.population
            val secondPopulation = second.population
            if (firstPopulation / secondPopulation.toFloat() < POPULATION_FRACTION_FOR_MORE_VIBRANT) second else first
        } else if (firstValid) {
            first
        } else if (secondValid) {
            second
        } else null
    }

    private fun hasEnoughPopulation(swatch: Palette.Swatch): Boolean {
        return swatch.population / RESIZE_BITMAP_AREA.toFloat() > MINIMUM_IMAGE_FRACTION
    }

    private fun isWhiteOrBlack(hsl: FloatArray): Boolean {
        return isBlack(hsl) || isWhite(hsl)
    }

    private fun isBlack(hslColor: FloatArray): Boolean {
        return hslColor[2] <= BLACK_MAX_LIGHTNESS
    }

    private fun isWhite(hslColor: FloatArray): Boolean {
        return hslColor[2] >= WHITE_MIN_LIGHTNESS
    }

    private fun ensureColors(backgroundColor: Int, mForegroundColor: Int): Pair<Int, Int> {
        var primaryTextColor: Int
        var secondaryTextColor: Int
        val backLum = NotificationColorUtil.calculateLuminance(backgroundColor)
        val textLum = NotificationColorUtil.calculateLuminance(mForegroundColor)
        val contrast = NotificationColorUtil.calculateContrast(mForegroundColor, backgroundColor)

        val backgroundLight = (backLum > textLum && NotificationColorUtil.satisfiesTextContrast(backgroundColor, Color.BLACK)) ||
                (backLum <= textLum && !NotificationColorUtil.satisfiesTextContrast(backgroundColor, Color.WHITE))

        if (contrast < 4.5f) {
            if (backgroundLight) {
                secondaryTextColor = NotificationColorUtil.findContrastColor(mForegroundColor, backgroundColor, true, 4.5)
                primaryTextColor = NotificationColorUtil.changeColorLightness(secondaryTextColor, -LIGHTNESS_TEXT_DIFFERENCE_LIGHT)
            } else {
                secondaryTextColor = NotificationColorUtil.findContrastColorAgainstDark(mForegroundColor, backgroundColor, true, 4.5)
                primaryTextColor = NotificationColorUtil.changeColorLightness(secondaryTextColor, -LIGHTNESS_TEXT_DIFFERENCE_DARK)
            }
        } else {
            primaryTextColor = mForegroundColor
            secondaryTextColor = NotificationColorUtil.changeColorLightness(
                primaryTextColor, if (backgroundLight) LIGHTNESS_TEXT_DIFFERENCE_LIGHT else LIGHTNESS_TEXT_DIFFERENCE_DARK
            )
            if (NotificationColorUtil.calculateContrast(secondaryTextColor, backgroundColor) < 4.5f) {
                secondaryTextColor = if (backgroundLight) {
                    NotificationColorUtil.findContrastColor(secondaryTextColor, backgroundColor, true, 4.5)
                } else {
                    NotificationColorUtil.findContrastColorAgainstDark(secondaryTextColor, backgroundColor, true, 4.5)
                }
                primaryTextColor = NotificationColorUtil.changeColorLightness(
                    secondaryTextColor,
                    if (backgroundLight) -LIGHTNESS_TEXT_DIFFERENCE_LIGHT else -LIGHTNESS_TEXT_DIFFERENCE_DARK
                )
            }
        }
        return primaryTextColor to secondaryTextColor
    }
}