/*
 * Copyright (c) 2026 BoomingFlow contributors
 * Replicates SpatialFlow's imageResolve() pre-blur pixel passes (GPL-3.0)
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

package com.mardous.booming.ui.component.compose.decoration

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import coil3.size.Size
import coil3.transform.Transformation

/**
 * The pixel passes SpatialFlow runs BEFORE its blur:
 * 1. Saturation boosted 2.0x via ColorMatrix paint.
 * 2. Dark tint pass 1: 0x33000000 with OVERLAY blend.
 * 3. Dark tint pass 2: 0x40000000 plain.
 * Chained before [com.commit451.coiltransformations.BlurTransformation],
 * so the blur averages the saturated/tinted colors - the source of the
 * smoother, softer SpatialFlow look.
 */
class SpatialTintTransformation : Transformation {

    override val cacheKey: String = "spatial_tint"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // Hardware bitmaps can't be drawn onto a software canvas.
        val source = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false) ?: input
        } else {
            input
        }
        val saturationPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(2f) })
        }
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(result).applyCanvas {
            drawBitmap(source, 0f, 0f, saturationPaint)
            drawColor(0x33000000, PorterDuff.Mode.OVERLAY)
            drawColor(0x40000000)
        }
        return result
    }
}
