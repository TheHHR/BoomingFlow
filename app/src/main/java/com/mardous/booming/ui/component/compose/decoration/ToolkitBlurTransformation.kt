/*
 * Copyright (c) 2026 BoomingFlow contributors
 * Uses Google's RenderScript Toolkit (Apache-2.0), the exact blur engine
 * SpatialFlow uses (GPL-3.0 composition).
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
import coil3.size.Size
import coil3.transform.Transformation
import com.google.android.renderscript.Toolkit

/**
 * The exact blur engine of SpatialFlow's background: Google's RenderScript
 * Toolkit Gaussian, called verbatim ([Toolkit.blur]). Runs on the already
 * saturated/tinted 96px bitmap, so the effective softness matches
 * SpatialFlow's background bit-for-bit.
 */
class ToolkitBlurTransformation(
    private val radius: Int = 12
) : Transformation() {

    override val cacheKey: String = "toolkit_blur_$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // Toolkit requires non-HARDWARE ARGB input.
        val source = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false) ?: input
        } else {
            input
        }
        return Toolkit.blur(source, radius)
    }
}
