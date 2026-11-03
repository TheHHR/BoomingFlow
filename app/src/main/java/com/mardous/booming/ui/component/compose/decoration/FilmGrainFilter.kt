/*
 * Copyright (c) 2026 BoomingFlow contributors
 * Ported from SpatialFlow (FilmGrainFilter.kt), GPL-3.0
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
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ShaderBrush
import java.util.Random

/**
 * AGSL (Android Graphics Shading Language) Fragment Shader for procedural film grain.
 * Uses Dave Hoskins hash (pure MAD + fract, no sin/cos) for single-cycle execution
 * on mobile GPUs without driver precision divergence.
 */
private const val AGSL_GRAIN_SHADER = """
    uniform float2 uResolution;

    float hash(float2 p) {
        float3 p3 = fract(float3(p.xyx) * 0.1031);
        p3 += dot(p3, p3.yzx + 33.33);
        return fract((p3.x + p3.y) * p3.z);
    }

    half4 main(float2 fragCoord) {
        float n = hash(fragCoord);
        return half4(n, n, n, 1.0);
    }
"""

/**
 * Thread-safe singleton cache for legacy Android versions (API 25-32).
 * Pre-computes a seamless 128x128 monochrome noise tile once (<64 KB RAM)
 * and tiles it via hardware-accelerated [BitmapShader].
 */
internal object NoiseTileCache {
    @Volatile
    private var cachedShader: BitmapShader? = null

    fun getShader(): BitmapShader {
        val existing = cachedShader
        if (existing != null) return existing

        return synchronized(this) {
            cachedShader ?: run {
                val size = 128
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(size * size)
                val rng = Random(1337L)
                for (i in pixels.indices) {
                    val n = rng.nextInt(256)
                    pixels[i] = (0xFF shl 24) or (n shl 16) or (n shl 8) or n
                }
                bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
                val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                cachedShader = shader
                shader
            }
        }
    }
}

/**
 * Renders an Apple Music-style tactile film grain overlay, exactly as
 * SpatialFlow draws it over the Spatial fluid background.
 *
 * Performance characteristics:
 * - Android 13+ (API 33+): Procedural AGSL [RuntimeShader] on GPU (0 MB bitmap allocation).
 * - Android 7.1 to 12 (API 25-32): Hardware-tiled [BitmapShader] (<64 KB heap allocation).
 * - Zero recompositions: Reads [alphaProvider] strictly during the Draw phase via [drawWithCache].
 *
 * @param alphaProvider Lambda providing the transition alpha (1f = fully visible).
 * @param intensity Grain opacity factor (SpatialFlow uses 0.055f).
 * @param enabled Whether the effect is active according to user settings.
 */
@Composable
fun FilmGrainLayer(
    alphaProvider: () -> Float,
    modifier: Modifier = Modifier,
    intensity: Float = 0.055f,
    enabled: Boolean = true
) {
    if (!enabled) return

    val isAtLeastTiramisu = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU }
    val runtimeShader = remember(isAtLeastTiramisu) {
        if (isAtLeastTiramisu) {
            try {
                RuntimeShader(AGSL_GRAIN_SHADER)
            } catch (_: Throwable) {
                null
            }
        } else null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val brush = if (runtimeShader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runtimeShader.setFloatUniform("uResolution", size.width, size.height)
                    ShaderBrush(runtimeShader)
                } else {
                    ShaderBrush(NoiseTileCache.getShader())
                }

                onDrawBehind {
                    val currentAlpha = alphaProvider()
                    if (currentAlpha > 0.001f) {
                        drawRect(
                            brush = brush,
                            alpha = (currentAlpha * intensity).coerceIn(0f, 1f),
                            blendMode = BlendMode.Overlay
                        )
                    }
                }
            }
    )
}
