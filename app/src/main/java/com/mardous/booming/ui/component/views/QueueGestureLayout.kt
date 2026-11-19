/*
 * Copyright (c) 2026 BoomingFlow
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
package com.mardous.booming.ui.component.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.abs

/**
 * The now playing screen's root: watches EVERY touch entering the player
 * and claims the stream mid-flight once an upward drag crosses the swipe
 * gate, so the queue gesture works from anywhere on the screen.
 *
 * The gate runs in [dispatchTouchEvent], not onInterceptTouchEvent: a
 * scrolling Compose child (the cover lyrics) calls
 * requestDisallowInterceptTouchEvent as soon as it moves, which switches
 * interception off for the rest of the stream - that is why the swipe was
 * dead over the cover lyrics. dispatchTouchEvent always runs first, so on a
 * successful claim the children get ACTION_CANCEL and the rest of the
 * stream is consumed here.
 *
 * Downward drags are never claimed (swipe-to-collapse keeps its owner:
 * the bottom sheet behavior), horizontal drags never cross the 2:1
 * verticality gate (cover paging is untouched), and ACTION_DOWN is never
 * claimed (taps, long-presses and sliders keep working). The root must be
 * clickable so empty-area touches keep delivering events here.
 */
class QueueGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    /**
     * Called at most once per gesture, when the upward swipe gate is
     * crossed. Returns true if the queue opened; only then is the stream
     * claimed, so a disabled setting or the fullscreen lyrics takeover
     * leaves the gesture with its child. When unset the layout is a pure
     * pass-through container.
     */
    var queueSwipeListener: (() -> Boolean)? = null

    /**
     * Window-coordinate hit test for areas that keep their own vertical
     * swipe (the cover lyrics lines). A gesture whose DOWN lands inside is
     * never claimed.
     */
    var swipeExclusion: ((windowX: Float, windowY: Float) -> Boolean)? = null

    private val swipeThresholdPx = context.resources.displayMetrics.density * SWIPE_UP_DP
    private val windowLocation = IntArray(2)

    private var downX = 0f
    private var downY = 0f
    private var gateChecked = false
    private var swipeClaimed = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swipeClaimed = false
                getLocationInWindow(windowLocation)
                // An excluded start (lyric lines) skips the gate for the whole gesture.
                gateChecked = swipeExclusion?.invoke(
                    windowLocation[0] + ev.x,
                    windowLocation[1] + ev.y
                ) == true
            }

            MotionEvent.ACTION_MOVE -> if (!swipeClaimed && !gateChecked && crossesGate(ev)) {
                gateChecked = true
                if (queueSwipeListener?.invoke() == true) {
                    swipeClaimed = true
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
            }
        }
        if (swipeClaimed) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swipeClaimed = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun crossesGate(ev: MotionEvent): Boolean {
        if (queueSwipeListener == null || ev.pointerCount != 1) return false
        val dx = ev.x - downX
        val dy = ev.y - downY
        return -dy >= swipeThresholdPx && abs(dy) >= abs(dx) * 2
    }

    companion object {
        /** Upward travel (dp) that opens the queue, wherever the swipe starts. */
        private const val SWIPE_UP_DP = 48f
    }
}
