/*
 * Copyright (c) 2026 BoomingFlow contributors
 * Ported from SpatialFlow (FluidShaderBackground.kt, GPL-3.0)
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

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val AGSL_SPATIAL_SHADER = """
uniform float2 u_resolution;
uniform float u_time;
uniform float4 u_color1;
uniform float4 u_color2;
uniform float4 u_color3;
uniform float4 u_colorBg;

half4 main(in float2 fragCoord) {
    float2 uv = fragCoord / u_resolution;

    float2 b1 = float2(0.30 + 0.22 * sin(u_time * 0.40) + 0.06 * cos(u_time * 0.29),
                       0.25 + 0.15 * cos(u_time * 0.51) + 0.05 * sin(u_time * 0.36));

    float2 b2 = float2(0.72 + 0.16 * cos(u_time * 0.40) + 0.07 * sin(u_time * 0.33),
                       0.50 + 0.20 * sin(u_time * 0.47) + 0.04 * cos(u_time * 0.27));

    float2 b3 = float2(0.42 + 0.18 * sin(u_time * 0.28) + 0.08 * cos(u_time * 0.45),
                       0.78 + 0.11 * cos(u_time * 0.57) + 0.06 * sin(u_time * 0.24));

    float d1 = length(uv - b1);
    float d2 = length(uv - b2);
    float d3 = length(uv - b3);

    float w1 = smoothstep(0.75, 0.0, d1);
    float w2 = smoothstep(0.80, 0.0, d2);
    float w3 = smoothstep(0.70, 0.0, d3);

    float4 color = u_colorBg;
    color = mix(color, u_color1, w1 * 0.60);
    color = mix(color, u_color2, w2 * 0.55);
    color = mix(color, u_color3, w3 * 0.45);

    float2 centerDist = uv - float2(0.5, 0.5);
    float vignette = smoothstep(0.85, 0.25, length(centerDist));
    color.rgb *= (0.55 + 0.45 * vignette);

    return half4(color);
}
"""

@Composable
private fun isReducedMotionEnabled(): Boolean = LocalContext.current.let { context ->
    remember(context) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
}

/**
 * GPU-accelerated dynamic fluid shader background ("Spatial"). Uses an AGSL
 * RuntimeShader on Android 13+ (API 33+); earlier versions get a Canvas
 * fallback that replicates the same three drifting color blobs. Renders in
 * the draw phase, so frames never trigger recomposition. The motion loop
 * always runs (60s cycle, exactly like SpatialFlow) - no pause-linked
 * animation switching.
 */
@Composable
fun Modifier.spatialBackground(
    backgroundColor: Color,
    vibrant: Color,
    darkVibrant: Color,
    darkMuted: Color
): Modifier {
    if (isReducedMotionEnabled()) {
        return this.background(
            Brush.verticalGradient(
                colors = listOf(backgroundColor, vibrant.copy(alpha = 0.25f))
            )
        )
    }

    val animatedBg by animateColorAsState(
        backgroundColor,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "spatial_bg_color"
    )
    val animatedVibrant by animateColorAsState(
        vibrant,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "spatial_vibrant_color"
    )
    val animatedDarkVibrant by animateColorAsState(
        darkVibrant,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "spatial_dark_vibrant_color"
    )
    val animatedDarkMuted by animateColorAsState(
        darkMuted,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "spatial_dark_muted_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "spatial_shader_time")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat() * 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spatial_time"
    )

    return this
        .graphicsLayer()
        .drawWithCache {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val runtimeShader = RuntimeShader(AGSL_SPATIAL_SHADER)
                val shaderBrush = ShaderBrush(runtimeShader)

                onDrawBehind {
                    runtimeShader.setFloatUniform("u_resolution", size.width, size.height)
                    runtimeShader.setFloatUniform("u_time", time)
                    runtimeShader.setFloatUniform(
                        "u_color1",
                        animatedVibrant.red, animatedVibrant.green, animatedVibrant.blue, animatedVibrant.alpha
                    )
                    runtimeShader.setFloatUniform(
                        "u_color2",
                        animatedDarkVibrant.red, animatedDarkVibrant.green, animatedDarkVibrant.blue, animatedDarkVibrant.alpha
                    )
                    runtimeShader.setFloatUniform(
                        "u_color3",
                        animatedDarkMuted.red, animatedDarkMuted.green, animatedDarkMuted.blue, animatedDarkMuted.alpha
                    )
                    runtimeShader.setFloatUniform(
                        "u_colorBg",
                        animatedBg.red, animatedBg.green, animatedBg.blue, animatedBg.alpha
                    )

                    drawRect(shaderBrush)
                }
            } else {
                val colorList1 = listOf(animatedVibrant.copy(alpha = 0.50f), Color.Transparent)
                val colorList2 = listOf(animatedDarkVibrant.copy(alpha = 0.45f), Color.Transparent)
                val colorList3 = listOf(animatedDarkMuted.copy(alpha = 0.40f), Color.Transparent)

                onDrawBehind {
                    drawRect(color = animatedBg)

                    val width = size.width
                    val height = size.height
                    val diagonal = hypot(width, height)

                    val b1X = width * (0.30f + 0.22f * sin(time * 0.40f) + 0.06f * cos(time * 0.29f))
                    val b1Y = height * (0.25f + 0.15f * cos(time * 0.51f) + 0.05f * sin(time * 0.36f))
                    val r1 = diagonal * 0.65f
                    val b1Center = Offset(b1X, b1Y)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = colorList1,
                            center = b1Center,
                            radius = r1
                        ),
                        center = b1Center,
                        radius = r1
                    )

                    val b2X = width * (0.72f + 0.16f * cos(time * 0.40f) + 0.07f * sin(time * 0.33f))
                    val b2Y = height * (0.50f + 0.20f * sin(time * 0.47f) + 0.04f * cos(time * 0.27f))
                    val r2 = diagonal * 0.70f
                    val b2Center = Offset(b2X, b2Y)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = colorList2,
                            center = b2Center,
                            radius = r2
                        ),
                        center = b2Center,
                        radius = r2
                    )

                    val b3X = width * (0.42f + 0.18f * sin(time * 0.28f) + 0.08f * cos(time * 0.45f))
                    val b3Y = height * (0.78f + 0.11f * cos(time * 0.57f) + 0.06f * sin(time * 0.24f))
                    val r3 = diagonal * 0.60f
                    val b3Center = Offset(b3X, b3Y)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = colorList3,
                            center = b3Center,
                            radius = r3
                        ),
                        center = b3Center,
                        radius = r3
                    )
                }
            }
        }
}
