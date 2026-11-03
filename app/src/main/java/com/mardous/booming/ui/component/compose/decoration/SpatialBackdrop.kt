/*
 * Copyright (c) 2026 BoomingFlow contributors
 * Ported from SpatialFlow (SpatialFloatingLightView.kt + NonCanvasPaletteOverlay
 * + FilmGrainFilter.kt), GPL-3.0
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

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Scale
import coil3.toBitmap
import com.flaviofaria.kenburnsview.KenBurnsView
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import com.mardous.booming.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap

private enum class KenBurnsOption {
    Init,
    Pause,
    Resume
}

/**
 * SpatialFlow's BlurredDrawableCache: a heap-proportional LruCache
 * (1/32 of max heap, safely bounded 2-8 MB) for finished blurred covers,
 * plus in-flight request deduplication so concurrent loads of the same
 * art share a single blur computation instead of racing.
 */
private object BlurredDrawableCache {
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 32).coerceIn(2048, 8192)

    private val cache = object : LruCache<String, Drawable>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Drawable): Int {
            val bitmap = (value as? BitmapDrawable)?.bitmap
            return if (bitmap != null && !bitmap.isRecycled) {
                (bitmap.byteCount / 1024).coerceAtLeast(1)
            } else {
                64
            }
        }
    }

    private val inFlightRequests = ConcurrentHashMap<String, Deferred<Drawable?>>()

    fun get(key: String): Drawable? = synchronized(cache) { cache.get(key) }

    private fun put(key: String, drawable: Drawable) {
        synchronized(cache) { cache.put(key, drawable) }
    }

    suspend fun getOrCompute(
        key: String,
        compute: suspend () -> Drawable?
    ): Drawable? {
        get(key)?.let { return it }

        val completer = CompletableDeferred<Drawable?>()
        val existing = inFlightRequests.putIfAbsent(key, completer)
        if (existing != null) {
            return try {
                existing.await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                compute()
            }
            completer.complete(result)
            if (result != null) {
                put(key, result)
            }
            result
        } catch (e: Throwable) {
            completer.completeExceptionally(e)
            if (e is CancellationException) throw e
            null
        } finally {
            inFlightRequests.remove(key)
        }
    }
}

/**
 * SpatialFlow's reduced-motion check: system animator scale set to 0
 * (accessibility "remove animations") freezes KenBurns, skips the spring
 * color morphs and renders the dim statically.
 */
private fun Context.isReducedMotionEnabled(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } else {
        false
    }

private fun coverCacheKey(model: Any?): String? = when (model) {
    null -> null
    is Song -> "song_${model.id}"
    else -> model.toString()
}

/**
 * The Spatial now playing background, matching SpatialFlow's visible
 * composition:
 * 1. Album cover downscaled to a 96px square, tint passes applied pre-blur
 *    ([SpatialTintTransformation]: 2.0x saturation + OVERLAY/plain tints),
 *    then blurred (radius 12 via Google's RenderScript Toolkit - the same
 *    engine SpatialFlow uses). Served through an LruCache (1/32 of max
 *    heap, 2-8 MB) with in-flight request deduplication, exactly like
 *    SpatialFlow's BlurredDrawableCache. Applied exactly once per cover.
 * 2. KenBurns slow pan/zoom motion (12s random transitions) while the
 *    player is playing AND the host is resumed (SpatialFlow's gating);
 *    frozen frame when paused, backgrounded, toggled off, or under
 *    reduced motion. With the toggle off, the same blurred cover is
 *    shown as a static image.
 * 3. A STATIC vertical palette gradient
 *    (vibrant 30% -> dark-vibrant 20% -> dark-muted 15%), spring-morphed
 *    on song change with SpatialFlow's NonCanvasPaletteOverlay specs.
 * 4. Animated black dim (player page: 0.42 * 0.618 like SpatialFlow's
 *    lossEffect; lyrics page: fades out).
 * 5. Apple Music-style tactile film grain (SpatialFlow's FilmGrainLayer:
 *    AGSL on Android 13+, tiled noise bitmap below), applied over both
 *    the player and lyrics pages.
 * The only "animation" besides KenBurns is the spring color morph on
 * song change and the dim fade; all of it is suppressed under reduced
 * motion.
 */
@Composable
fun SpatialBackdrop(
    coverModel: Any?,
    vibrant: Color,
    darkVibrant: Color,
    darkMuted: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    kenBurnsEnabled: Boolean = true,
    filmGrainEnabled: Boolean = true
) {
    val context = LocalContext.current
    val isReducedMotion = remember(context) { context.isReducedMotionEnabled() }

    // SpatialFlow's NonCanvasPaletteOverlay spring specs.
    val colorSpec = spring<Color>(stiffness = 100f, dampingRatio = 0.8f)
    val animatedVibrant = if (isReducedMotion) {
        vibrant
    } else {
        animateColorAsState(vibrant, colorSpec, label = "spatial_vibrant").value
    }
    val animatedDarkVibrant = if (isReducedMotion) {
        darkVibrant
    } else {
        animateColorAsState(darkVibrant, colorSpec, label = "spatial_dark_vibrant").value
    }
    val animatedDarkMuted = if (isReducedMotion) {
        darkMuted
    } else {
        animateColorAsState(darkMuted, colorSpec, label = "spatial_dark_muted").value
    }

    // SpatialFloatingLight's lossEffect: tweened 0 <-> 0.618 dim progress.
    val dimTarget = if (dimmed) 0.618f else 0f
    val dimAlpha = if (isReducedMotion) {
        dimTarget
    } else {
        animateFloatAsState(
            targetValue = dimTarget,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            label = "spatial_loss_effect"
        ).value
    }

    val coverKey = remember(coverModel) { coverCacheKey(coverModel) }
    var blurredCover by remember(coverKey) {
        mutableStateOf(coverKey?.let { BlurredDrawableCache.get(it) })
    }

    LaunchedEffect(coverKey) {
        val key = coverKey ?: return@LaunchedEffect
        if (blurredCover != null) return@LaunchedEffect
        blurredCover = BlurredDrawableCache.getOrCompute(key) {
            val request = ImageRequest.Builder(context)
                .data(coverModel)
                .size(96, 96)
                .scale(Scale.FILL)
                .allowHardware(false)
                .transformations(
                    SpatialTintTransformation(),
                    ToolkitBlurTransformation(radius = 12)
                )
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap()
                ?: return@getOrCompute null
            BitmapDrawable(context.resources, bitmap)
        }
    }

    val animateMotion = kenBurnsEnabled && !isReducedMotion
    val lifecycleOwner = LocalLifecycleOwner.current
    val isResumed by lifecycleOwner.lifecycle.currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.RESUMED) }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(
            initialValue = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
        )

    Box(modifier.background(Color.Black)) {
        if (animateMotion) {
            val lastOption = remember { mutableStateOf(KenBurnsOption.Init) }
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
                    val currentDrawable = blurredCover
                    if (currentDrawable != null && view.drawable !== currentDrawable) {
                        view.setImageDrawable(currentDrawable)
                    }
                    // SpatialFloatingLight's gating: animate only while the
                    // host is resumed and playback is active (the mini-player
                    // gate is N/A here - the player is a full screen).
                    val shouldAnimate = isResumed && isPlaying
                    if (shouldAnimate) {
                        if (lastOption.value != KenBurnsOption.Resume) {
                            view.resume()
                            lastOption.value = KenBurnsOption.Resume
                        }
                    } else {
                        if (lastOption.value != KenBurnsOption.Pause) {
                            view.pause()
                            lastOption.value = KenBurnsOption.Pause
                        }
                    }
                }
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                modifier = Modifier.matchParentSize(),
                onRelease = { view ->
                    view.setImageDrawable(null)
                },
                update = { view ->
                    val currentDrawable = blurredCover
                    if (view.drawable !== currentDrawable) {
                        view.setImageDrawable(currentDrawable)
                    }
                }
            )
        }

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

        FilmGrainLayer(
            alphaProvider = { 1f },
            intensity = 0.055f,
            enabled = filmGrainEnabled,
            modifier = Modifier.matchParentSize()
        )
    }
}
