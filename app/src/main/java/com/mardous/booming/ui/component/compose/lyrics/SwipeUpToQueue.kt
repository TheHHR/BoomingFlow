package com.mardous.booming.ui.component.compose.lyrics

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.dp
import com.mardous.booming.ui.component.views.QueueGestureLayout
import kotlin.math.abs

/**
 * Observe-only swipe-up-to-queue trigger for the mini cover lyrics.
 *
 * The player root's queue gesture ([QueueGestureLayout]) can never claim a
 * touch stream the lyrics' Compose scrollable has consumed:
 * `AndroidComposeView.dispatchTouchEvent` calls
 * `requestDisallowInterceptTouchEvent(true)` up the View chain for ANY
 * consumed movement, so [QueueGestureLayout.onInterceptTouchEvent] is
 * skipped for the rest of the gesture. That is by design - drags that
 * start on the lyrics must keep scrolling them.
 *
 * The knowledge "can these lyrics still scroll forward?", however, lives
 * inside Compose. This observer, attached on the lyrics' own region,
 * watches the tracked pointer WITHOUT consuming anything (the scrollable
 * keeps owning the gesture; it never even sees a difference) and
 * accumulates the upward displacement the finger travels WHILE the lyrics
 * are at their end. Crossing the same 48dp / 2:1 gate the player root
 * uses fires [onSwipeUp] mid-drag: a swipe that starts on the lyrics
 * scrolls them, and once they are out of content the same continued
 * swipe opens the queue. The accumulation resets whenever
 * [canScrollForward] flips, so merely scrolling TO the end never fires
 * by itself - only dragging 48dp further upward does.
 */
@Composable
fun Modifier.swipeUpToQueueAtEnd(
    canScrollForward: () -> Boolean,
    onSwipeUp: (() -> Unit)?
): Modifier {
    if (onSwipeUp == null) {
        return this
    }
    val liveCanScrollForward by rememberUpdatedState(canScrollForward)
    val liveOnSwipeUp by rememberUpdatedState(onSwipeUp)

    return pointerInput(Unit) {
        // Same 48dp the player root's gate travels before opening the
        // queue, so both triggers feel identical.
        val swipeUpThresholdPx = QueueGestureLayout.SWIPE_UP_DP.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var atEnd = !liveCanScrollForward()
            var dyAtEnd = 0f
            var dxAtEnd = 0f
            var fired = false
            while (!fired) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // positionChangeIgnoreConsumed(): the scrollable consumes
                // the drag, and the plain positionChange() of a consumed
                // change reports zero movement.
                val delta = change.positionChangeIgnoreConsumed()
                if (delta.x != 0f || delta.y != 0f) {
                    val nowAtEnd = !liveCanScrollForward()
                    if (nowAtEnd != atEnd) {
                        // The lyrics just ran out of (or regained) forward
                        // range: only the travel while stuck at the end
                        // counts toward the queue gesture.
                        atEnd = nowAtEnd
                        dyAtEnd = 0f
                        dxAtEnd = 0f
                    }
                    if (atEnd) {
                        dyAtEnd += delta.y
                        dxAtEnd += delta.x
                        if (-dyAtEnd >= swipeUpThresholdPx &&
                            abs(dyAtEnd) >= abs(dxAtEnd) * 2
                        ) {
                            fired = true
                            liveOnSwipeUp()
                        }
                    }
                }
            }
        }
    }
}
