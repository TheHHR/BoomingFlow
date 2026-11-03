/*
 * Copyright (c) 2026 BoomingFlow contributors
 * Ported from SpatialFlow (SpatialFloatingLightView.kt + NonCanvasPaletteOverlay, GPL-3.0)
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

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import com.commit451.coiltransformations.BlurTransformation

/**
 * The Spatial now playing background, replicating SpatialFlow's visible
 * composition exactly:
 * 1. Album cover downscaled to 128px, saturation boosted 2.0x, blurred
 *    (radius 12 on the small bitmap - the cheap RenderScript-style trick).
 * 2. Dark tint passes over the artwork (OVERLAY + plain) for contrast.
 * 3. A STATIC vertical palette gradient
 *    (vibrant 30% -> dark-vibrant 20% -> dark-muted 15%).
 * 4. Optional black dim (player: on, matching SpatialFlow's lossEffect;
 *    lyrics page: off).
 * No motion: the only "animation" is the spring color morph on song change.
 */
@Composable
fun SpatialBackdrop(
    coverModel: Any?,
    vibrant: Color,
    darkVibrant: Color,
    darkMuted: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(coverModel, context) {
        ImageRequest.Builder(context)
            .data(coverModel)
            .size(128)
            .transformations(BlurTransformation(context = context, radius = 12f))
            .build()
    }

    // SpatialFlow's NonCanvasPaletteOverlay spring specs.
    val colorSpec = spring<Color>(stiffness = 100f, dampingRatio = 0.8f)
    val animatedVibrant by animateColorAsState(vibrant, colorSpec, label = "spatial_vibrant")
    val animatedDarkVibrant by animateColorAsState(darkVibrant, colorSpec, label = "spatial_dark_vibrant")
    val animatedDarkMuted by animateColorAsState(darkMuted, colorSpec, label = "spatial_dark_muted")

    Box(modifier.background(Color.Black)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorFilter(
                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(2f) })
            ),
            modifier = Modifier.matchParentSize()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind {
                    // SpatialFloatingLight's imageResolve() tint passes.
                    drawRect(Color(0x33000000), blendMode = BlendMode.Overlay)
                    drawRect(Color(0x40000000))
                    // NonCanvasPaletteOverlay: static palette gradient.
                    drawRect(
                        Brush.verticalGradient(
                            listOf(
                                animatedVibrant.copy(alpha = 0.30f),
                                animatedDarkVibrant.copy(alpha = 0.20f),
                                animatedDarkMuted.copy(alpha = 0.15f)
                            )
                        )
                    )
                    // lossEffect: 0.42 alpha * 0.618 animated progress.
                    if (dimmed) drawRect(Color.Black.copy(alpha = 0.42f * 0.618f))
                }
        )
    }
}
