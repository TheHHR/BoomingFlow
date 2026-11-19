package com.mardous.booming.ui.component.compose.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * On-screen lyric lines of the cover lyrics. A swipe that starts inside the
 * span of the visible lines scrolls the lyrics; anywhere else the player's
 * QueueGestureLayout may open the queue. Main thread only (composition and
 * touch dispatch).
 */
class LyricsLineBounds {
    private val lines = HashMap<Any, LayoutCoordinates>()

    fun put(key: Any, coordinates: LayoutCoordinates) {
        lines[key] = coordinates
    }

    fun remove(key: Any) {
        lines.remove(key)
    }

    /**
     * True if the window point is inside the box spanning every visible line,
     * so the small gaps between lines still count as lyrics.
     */
    fun contains(windowX: Float, windowY: Float): Boolean {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (coordinates in lines.values) {
            if (!coordinates.isAttached) continue
            // boundsInWindow is clipped by the list viewport: off-screen lines are empty.
            val r = coordinates.boundsInWindow()
            if (r.isEmpty) continue
            if (r.left < left) left = r.left
            if (r.top < top) top = r.top
            if (r.right > right) right = r.right
            if (r.bottom > bottom) bottom = r.bottom
        }
        return windowX in left..right && windowY in top..bottom
    }
}

/** Provided only by the cover (non-fullscreen) lyrics; null elsewhere, which makes [lyricsLineBounds] a no-op. */
val LocalLyricsLineBounds = staticCompositionLocalOf<LyricsLineBounds?> { null }

@Composable
fun Modifier.lyricsLineBounds(key: Any): Modifier {
    val bounds = LocalLyricsLineBounds.current ?: return this
    DisposableEffect(bounds, key) {
        onDispose { bounds.remove(key) }
    }
    return onGloballyPositioned { bounds.put(key, it) }
}
