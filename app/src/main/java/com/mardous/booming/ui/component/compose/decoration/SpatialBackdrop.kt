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

import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import coil3.load
import coil3.request.transformations
import com.flaviofaria.kenburnsview.KenBurnsView
import com.flaviofaria.kenburnsview.RandomTransitionGenerator

/**
 * The Spatial now playing background, matching SpatialFlow's visible
 * composition:
 * 1. Album cover downscaled to 96px, tint passes applied pre-blur
 *    ([SpatialTintTransformation]: 2.0x saturation + OVERLAY/plain tints),
 *    then blurred (radius 12 on the small bitmap - the cheap
 *    RenderScript-style trick). Applied exactly once, like SpatialFlow.
 * 2. KenBurns slow pan/zoom motion (12s random transitions) while the
 *    player is playing; frozen frame when paused.
 * 3. A STATIC vertical palette gradient
 *    (vibrant 30% -> dark-vibrant 20% -> dark-muted 15%).
 * 4. Animated black dim (player: fades to 0.42 * 0.618 like SpatialFlow's
 *    lossEffect; lyrics page: fades out). The only "animation" besides
 *    KenBurns is the spring color morph on song change.
 */
@Composable
fun SpatialBackdrop(
    coverModel: Any?,
    vibrant: Color,
    darkVibrant: Color,
    darkMuted: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true
) {
    // SpatialFlow's NonCanvasPaletteOverlay spring specs.
    val colorSpec = spring<Color>(stiffness = 100f, dampingRatio = 0.8f)
    val animatedVibrant by animateColorAsState(vibrant, colorSpec, label = "spatial_vibrant")
    val animatedDarkVibrant by animateColorAsState(darkVibrant, colorSpec, label = "spatial_dark_vibrant")
    val animatedDarkMuted by animateColorAsState(darkMuted, colorSpec, label = "spatial_dark_muted")

    // SpatialFloatingLight's lossEffect: tweened 0 <-> 0.618 dim progress.
    val dimAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.618f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "spatial_loss_effect"
    )

    var loadedModel by remember { mutableStateOf<Any?>(null) }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                KenBurnsView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setTransitionGenerator(
                        RandomTransitionGenerator(
                            12000,
                            AccelerateDecelerateInterpolator()
                        )
                    )
                }
            },
            modifier = Modifier.matchParentSize(),
            onRelease = { view ->
                view.pause()
                view.setImageDrawable(null)
            },
            update = { view ->
                val model = coverModel
                if (model != null && loadedModel != model) {
                    loadedModel = model
                    view.load(model) {
                        // SpatialFlow pipeline: 96px, tint passes, Toolkit.blur(12).
                        size(96)
                        transformations(
                            SpatialTintTransformation(),
                            ToolkitBlurTransformation(radius = 12)
                        )
                    }
                }
                if (isPlaying) {
                    view.resume()
                } else {
                    view.pause()
                }
            }
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
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
                    if (dimAlpha > 0f) drawRect(Color.Black.copy(alpha = 0.42f * dimAlpha))
                }
        )
    }
}
