/*
 * Copyright (c) 2024 Christians Martínez Alvarado
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

package com.mardous.booming.extensions.resources

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.style.ForegroundColorSpan
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.appcompat.view.menu.ActionMenuItemView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.SwitchCompat
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.ImageView
import android.view.View
import com.mardous.booming.core.model.theme.AccentSchemes
import com.google.android.material.slider.Slider
import com.mardous.booming.R
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.ui.component.views.MorphicIconButton
import kotlin.math.abs

val Int.isColorLight: Boolean
    get() = (1 - (0.299 * Color.red(this) + 0.587 * Color.green(this) + 0.114 * Color.blue(this)) / 255) < 0.4

val Int.darkenColor: Int
    get() = shiftColor(0.9f)

/**
 * Ensures that the color has enough contrast against a given background.
 */
@ColorInt
fun Int.ensureContrastAgainst(@ColorInt background: Int, minContrastRatio: Double = 2.0): Int {
    return if (ColorUtils.calculateContrast(this, background) < minContrastRatio) {
        if (ColorUtils.calculateLuminance(background) > 0.5)
            ColorUtils.blendARGB(this, 0xFF000000.toInt(), 0.3f)
        else
            ColorUtils.blendARGB(this, 0xFFFFFFFF.toInt(), 0.3f)
    } else {
        this
    }
}

/**
 * Desaturates the color if it's too dark compared to the reference background.
 */
@ColorInt
fun Int.desaturateIfTooDarkComparedTo(@ColorInt background: Int): Int {
    val luminanceDiff = ColorUtils.calculateLuminance(background) - ColorUtils.calculateLuminance(this)
    return if (luminanceDiff > 0.3) ColorUtils.blendARGB(this, background, 0.3f) else this
}

fun Int.adjustSaturationIfTooHigh(surfaceColor: Int, isNightMode: Boolean): Int {
    if (isNightMode) return this

    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this, hsl)

    val backgroundLuminance = ColorUtils.calculateLuminance(surfaceColor)
    val colorLuminance = ColorUtils.calculateLuminance(this)

    val delta = abs(colorLuminance - backgroundLuminance)

    if (hsl[1] > 0.5f && delta < 0.3f) {
        hsl[1] = 0.4f + (hsl[1] - 0.5f) * 0.5f
    }

    return ColorUtils.HSLToColor(hsl)
}

/**
 * Boosts the color's saturation to at least [minSaturation], keeping hue and lightness.
 */
@ColorInt
fun Int.boostSaturation(minSaturation: Float): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this, hsl)
    if (hsl[1] < minSaturation) {
        hsl[1] = minSaturation
        return ColorUtils.HSLToColor(hsl)
    }
    return this
}

fun Int.shiftColor(by: Float): Int {
    if (by == 1f) return this
    val alpha = Color.alpha(this)
    val hsv = FloatArray(3)
    Color.colorToHSV(this, hsv)
    hsv[2] *= by // value component

    return (alpha shl 24) + (0x00ffffff and Color.HSVToColor(hsv))
}

fun Int.withAlpha(alpha: Float): Int {
    val a = 255.coerceAtMost(0.coerceAtLeast((alpha * 255).toInt())) shl 24
    val rgb = 0x00ffffff and this
    return a + rgb
}

fun Int.toForegroundColorSpan() = ForegroundColorSpan(this)

fun Int.toColorStateList(): ColorStateList = ColorStateList.valueOf(this)

@ColorInt
fun Context.getColorCompat(@ColorRes res: Int) = ContextCompat.getColor(this, res)

@ColorInt
fun Fragment.surfaceColor() = requireContext().surfaceColor()

@ColorInt
fun Context.surfaceColor() = resolveColor(com.google.android.material.R.attr.colorSurface)

@ColorInt
fun Context.onSurfaceColor() = resolveColor(com.google.android.material.R.attr.colorOnSurface)

@ColorInt
fun Fragment.primaryColor() = requireContext().primaryColor()

@ColorInt
fun Context.primaryColor() = resolveColor(androidx.appcompat.R.attr.colorPrimary)

@ColorInt
fun Fragment.secondaryColor() = requireContext().secondaryColor()

@ColorInt
fun Context.secondaryColor() = resolveColor(com.google.android.material.R.attr.colorSecondary)

@ColorInt
fun Fragment.textColorPrimary() = requireContext().textColorPrimary()

@ColorInt
fun Context.textColorPrimary() = resolveColor(android.R.attr.textColorPrimary)

@ColorInt
fun Fragment.textColorSecondary() = requireContext().textColorSecondary()

@ColorInt
fun Context.textColorSecondary() = resolveColor(android.R.attr.textColorSecondary)

fun SeekBar.applyColor(@ColorInt color: Int) {
    color.toColorStateList().let {
        thumbTintList = it
        progressTintList = it
        progressBackgroundTintList = it
    }
}

fun Slider.applyColor(@ColorInt color: Int) {
    color.toColorStateList().run {
        thumbTintList = this
        trackActiveTintList = this
        trackInactiveTintList = ColorStateList.valueOf(color.withAlpha(0.1f))
    }
}

fun MaterialButton.applyColor(color: Int, isIconButton: Boolean = false) {
    if (isIconButton) {
        val iconTintList = color.toColorStateList()
        val strokeTintList = color.withAlpha(0.4f).toColorStateList()
        setTextColor(iconTintList)
        iconTint = iconTintList
        strokeColor = strokeTintList
    } else {
        val backgroundColorStateList = color.toColorStateList()
        val textColorColorStateList = getPrimaryTextColor(context, color.isColorLight).toColorStateList()
        backgroundTintList = backgroundColorStateList
        setTextColor(textColorColorStateList)
        iconTint = textColorColorStateList
    }
}

fun FloatingActionButton.applyColor(color: Int) {
    val textColor = getPrimaryTextColor(context, color.isColorLight)
    backgroundTintList = ColorStateList.valueOf(color)
    imageTintList = ColorStateList.valueOf(textColor)
}

fun TextView.applyColor(color: Int) {
    setTextColor(color)
    TextViewCompat.setCompoundDrawableTintList(this, color.toColorStateList())
}

fun MorphicIconButton.applyColor(color: Int) {
    backgroundTintList = color.toColorStateList()
    setIconTintColor(getPrimaryTextColor(context, color.isColorLight))
}

/** Themed defaults of a button, captured the first time an accent is applied. */
private class AccentOriginals(
    val backgroundTint: ColorStateList? = null,
    val textColors: ColorStateList? = null,
    val iconTint: ColorStateList? = null,
    val strokeColor: ColorStateList? = null,
    val switchTrack: ColorStateList? = null,
    val switchThumb: ColorStateList? = null,
    val iconColor: Int? = null
)

/**
 * Applies the player accent to this [MaterialButton]. An accent of 0
 * restores the themed defaults captured before the first application.
 * With [tonal] the button uses the accent scheme's secondary container
 * roles instead of a filled primary look (for secondary actions).
 */
fun MaterialButton.applyPlayerAccent(@ColorInt accent: Int, tonal: Boolean = false) {
    if (accent == 0) {
        (getTag(R.id.tag_accent_originals) as? AccentOriginals)?.let { original ->
            backgroundTintList = original.backgroundTint
            setTextColor(original.textColors)
            iconTint = original.iconTint
            strokeColor = original.strokeColor
        }
        return
    }
    if (getTag(R.id.tag_accent_originals) == null) {
        setTag(
            R.id.tag_accent_originals,
            AccentOriginals(backgroundTintList, textColors, iconTint, strokeColor)
        )
    }
    if (tonal) {
        val scheme = AccentSchemes.viewScheme(accent, context.isNightMode)
        val content = ColorStateList.valueOf(scheme.onSecondaryContainer)
        backgroundTintList = ColorStateList.valueOf(scheme.secondaryContainer)
        setTextColor(content)
        iconTint = content
    } else {
        applyColor(accent)
    }
}

/**
 * Applies the player accent to this [FloatingActionButton]. An accent of 0
 * ("no usable accent") restores the themed defaults captured before the
 * first application.
 */
fun FloatingActionButton.applyPlayerAccent(@ColorInt accent: Int) {
    if (accent == 0) {
        (getTag(R.id.tag_accent_originals) as? AccentOriginals)?.let { original ->
            backgroundTintList = original.backgroundTint
            imageTintList = original.iconTint
        }
        return
    }
    if (getTag(R.id.tag_accent_originals) == null) {
        setTag(
            R.id.tag_accent_originals,
            AccentOriginals(backgroundTintList, null, imageTintList, null)
        )
    }
    applyColor(accent)
}

/**
 * Applies the player accent to a preference-screen switch. An accent of 0
 * restores the themed defaults. Checked roles use the accent scheme
 * (primary/onPrimary); unchecked roles keep the themed defaults.
 */
fun SwitchCompat.applyPlayerAccent(@ColorInt accent: Int, @ColorInt checkedTrack: Int, @ColorInt checkedThumb: Int) {
    if (accent == 0) {
        (getTag(R.id.tag_accent_originals) as? AccentOriginals)?.let { original ->
            original.switchTrack?.let { trackTintList = it }
            original.switchThumb?.let { thumbTintList = it }
        }
        return
    }
    if (getTag(R.id.tag_accent_originals) == null) {
        setTag(
            R.id.tag_accent_originals,
            AccentOriginals(switchTrack = trackTintList, switchThumb = thumbTintList)
        )
    }
    val original = getTag(R.id.tag_accent_originals) as? AccentOriginals
    val states = arrayOf(
        intArrayOf(android.R.attr.state_checked),
        intArrayOf(-android.R.attr.state_checked)
    )
    trackTintList = ColorStateList(
        states,
        intArrayOf(checkedTrack, original?.switchTrack?.defaultColor ?: Color.GRAY)
    )
    thumbTintList = ColorStateList(
        states,
        intArrayOf(checkedThumb, original?.switchThumb?.defaultColor ?: Color.LTGRAY)
    )
}

/** Recursively applies [SwitchCompat.applyPlayerAccent] to every switch inside this hierarchy. */
fun ViewGroup.applyPreferenceSwitchAccent(@ColorInt accent: Int, @ColorInt checkedTrack: Int, @ColorInt checkedThumb: Int) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child is SwitchCompat) {
            child.applyPlayerAccent(accent, checkedTrack, checkedThumb)
        } else if (child is ViewGroup) {
            child.applyPreferenceSwitchAccent(accent, checkedTrack, checkedThumb)
        }
    }
}

/**
 * Recursively applies the player accent to toggle-group [MaterialButton]s
 * inside preference rows (checked state = scheme container roles). An
 * accent of 0 restores the themed defaults.
 */
fun ViewGroup.applyPreferenceToggleAccent(@ColorInt accent: Int, @ColorInt checkedBg: Int, @ColorInt checkedText: Int) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        if (child is MaterialButton) {
            child.applyTogglePlayerAccent(accent, checkedBg, checkedText)
        } else if (child is ViewGroup) {
            child.applyPreferenceToggleAccent(accent, checkedBg, checkedText)
        }
    }
}

/**
 * Recolors preference category header titles - the ones the theme draws
 * with the primary color - with the accent scheme's primary. An accent of
 * 0 restores the captured themed color.
 */
fun TextView.applyCategoryTitleAccent(@ColorInt accent: Int, @ColorInt themedPrimary: Int, @ColorInt accentPrimary: Int) {
    if (accent == 0) {
        (getTag(R.id.tag_accent_originals) as? AccentOriginals)?.let { original ->
            original.textColors?.let { setTextColor(it) }
        }
        return
    }
    if (getTag(R.id.tag_accent_originals) == null) {
        setTag(R.id.tag_accent_originals, AccentOriginals(textColors = textColors))
    }
    val original = getTag(R.id.tag_accent_originals) as? AccentOriginals
    val themedText = original?.textColors?.defaultColor ?: currentTextColor
    if (themedText == themedPrimary) {
        setTextColor(accentPrimary)
    }
}

/**
 * Applies the player accent to a toggle-group [MaterialButton]: the checked
 * state uses the accent scheme container roles, the unchecked state keeps
 * the themed defaults. An accent of 0 restores the themed defaults.
 */
fun MaterialButton.applyTogglePlayerAccent(@ColorInt accent: Int, @ColorInt checkedBg: Int, @ColorInt checkedText: Int) {
    if (getTag(R.id.tag_accent_originals) == null) {
        setTag(
            R.id.tag_accent_originals,
            AccentOriginals(
                backgroundTint = backgroundTintList,
                textColors = textColors,
                strokeColor = strokeColor
            )
        )
    }
    val original = getTag(R.id.tag_accent_originals) as? AccentOriginals
    if (accent == 0) {
        original?.let {
            it.backgroundTint?.let { backgroundTintList = it }
            it.textColors?.let { setTextColor(it) }
            it.strokeColor?.let { strokeColor = it }
        }
        return
    }
    // Derive the unchecked colors per-state from the ORIGINAL lists. The
    // query must include state_enabled + state_checkable: M3 expressive
    // button selectors list their DISABLED variant first, and a bare
    // [-state_checked] query would match that entry and give inactive
    // buttons the washed-out disabled colors instead of the real
    // unchecked look (container/onSurfaceVariant).
    val uncheckedState = intArrayOf(
        android.R.attr.state_enabled,
        android.R.attr.state_checkable,
        -android.R.attr.state_checked
    )
    val uncheckedBg = original?.backgroundTint
        ?.getColorForState(uncheckedState, Color.TRANSPARENT) ?: Color.TRANSPARENT
    val uncheckedText = original?.textColors
        ?.getColorForState(uncheckedState, currentTextColor) ?: currentTextColor
    val states = arrayOf(
        intArrayOf(android.R.attr.state_checked),
        uncheckedState
    )
    backgroundTintList = ColorStateList(states, intArrayOf(checkedBg, uncheckedBg))
    setTextColor(ColorStateList(states, intArrayOf(checkedText, uncheckedText)))
    val originalStroke = original?.strokeColor
    if (originalStroke != null) {
        val uncheckedStroke = originalStroke.getColorForState(uncheckedState, originalStroke.defaultColor)
        strokeColor = ColorStateList(states, intArrayOf(checkedBg, uncheckedStroke))
    }
}

/**
 * Tints a download badge with the accent scheme (background = primary,
 * check icon = onPrimary). An accent of 0 restores the drawable defaults.
 */
fun View.applyBadgePlayerAccent(@ColorInt accent: Int, @ColorInt bgColor: Int, @ColorInt iconColor: Int) {
    if (accent == 0) {
        backgroundTintList = null
        (this as? ImageView)?.imageTintList = null
    } else {
        backgroundTintList = ColorStateList.valueOf(bgColor)
        (this as? ImageView)?.imageTintList = ColorStateList.valueOf(iconColor)
    }
}

/**
 * Applies the player accent to a [MorphicIconButton] using the accent
 * scheme's primary container roles. An accent of 0 restores the captured
 * defaults.
 */
fun MorphicIconButton.applyPlayerAccent(@ColorInt accent: Int) {
    if (accent == 0) {
        (getTag(R.id.tag_accent_originals) as? AccentOriginals)?.let { original ->
            original.backgroundTint?.let { backgroundTintList = it }
            original.iconColor?.let { setIconTintColor(it) }
        }
        return
    }
    if (getTag(R.id.tag_accent_originals) == null) {
        setTag(
            R.id.tag_accent_originals,
            AccentOriginals(backgroundTint = backgroundTintList, iconColor = getIconTintColor())
        )
    }
    val scheme = AccentSchemes.viewScheme(accent, context.isNightMode)
    backgroundTintList = ColorStateList.valueOf(scheme.primaryContainer)
    setIconTintColor(scheme.onPrimaryContainer)
}

@SuppressLint("RestrictedApi")
fun Toolbar.colorizeToolbar(toolbarIconsColor: Int) {
    val colorFilter = PorterDuffColorFilter(toolbarIconsColor, PorterDuff.Mode.SRC_IN)

    for (i in 0 until childCount) {
        val v = getChildAt(i)

        if (v is ImageButton) {
            v.drawable?.mutate()?.colorFilter = colorFilter
        }

        if (v is ActionMenuView) {
            for (j in 0 until v.childCount) {
                val innerView = v.getChildAt(j)
                if (innerView is ActionMenuItemView) {
                    innerView.compoundDrawables.forEach { drawable ->
                        innerView.post {
                            drawable?.mutate()?.colorFilter = colorFilter
                        }
                    }
                }
            }
        }
    }

    setTitleTextColor(context.textColorSecondary())
    setSubtitleTextColor(context.textColorSecondary())

    overflowIcon?.mutate()?.colorFilter =
        PorterDuffColorFilter(toolbarIconsColor, PorterDuff.Mode.SRC_IN)
}

fun getPrimaryTextColor(context: Context, isDark: Boolean = !context.isNightMode, isDisabled: Boolean = false): Int {
    val resolveColor = if (isDark) {
        if (isDisabled) R.color.primary_text_disabled_light else R.color.primary_text_light
    } else {
        if (isDisabled) R.color.primary_text_disabled_dark else R.color.primary_text_dark
    }
    return context.getColorCompat(resolveColor)
}