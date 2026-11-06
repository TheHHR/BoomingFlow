package com.mardous.booming.ui.component.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.google.android.material.color.MaterialColors
import com.google.android.material.color.MaterialColors.getColorStateList
import com.mardous.booming.extensions.resources.getDrawableCompat
import kotlin.math.min
import com.google.android.material.R as M3R

fun Context.getPlaceholderDrawable(@DrawableRes foregroundRes: Int) =
    PlaceholderDrawable(this, foregroundRes)

fun Context.getPlaceholderDrawable(
    @DrawableRes foregroundRes: Int,
    @ColorInt background: Int,
    @ColorInt foreground: Int
) = PlaceholderDrawable(this, foregroundRes, background, foreground)

class PlaceholderDrawable(
    context: Context,
    @DrawableRes
    private val foregroundRes: Int,
    @ColorInt explicitBackground: Int? = null,
    @ColorInt explicitForeground: Int? = null
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = explicitBackground
            ?: MaterialColors.getColor(context, BACKGROUND_COLOR, Color.DKGRAY)
    }

    private val foreground = context.getDrawableCompat(foregroundRes)?.apply {
        setTintList(
            explicitForeground?.let { ColorStateList.valueOf(it) }
                ?: getColorStateList(context, FOREGROUND_COLOR, FALLBACK_TINT)
        )
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        canvas.drawRect(bounds, paint)

        foreground?.let {
            val size = (min(bounds.width(), bounds.height()) * 0.5f).toInt()
            val left = bounds.centerX() - size / 2
            val top = bounds.centerY() - size / 2
            val right = left + size
            val bottom = top + size
            it.setBounds(left, top, right, bottom)
            it.draw(canvas)
        }
    }

    @Deprecated("This is no longer used")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        private val FALLBACK_TINT = ColorStateList.valueOf(Color.WHITE)

        @AttrRes
        val BACKGROUND_COLOR = M3R.attr.colorSurfaceContainerHighest
        @AttrRes
        val FOREGROUND_COLOR = M3R.attr.colorOnSurfaceVariant
    }
}
