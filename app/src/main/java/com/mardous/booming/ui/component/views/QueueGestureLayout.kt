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
 * gate, so the queue gesture works from anywhere on the screen and cannot
 * be consumed by a child first.
 *
 * The gate is evaluated on both dispatch paths Android offers a ViewGroup:
 * [onInterceptTouchEvent] steals streams already claimed by child views
 * (album cover pager, controls, toolbar - their target receives
 * ACTION_CANCEL), while [onTouchEvent] covers empty-area touches, which
 * never reach interception because the group self-targets them.
 *
 * Downward drags are never claimed (swipe-to-collapse keeps its owner:
 * the bottom sheet behavior), horizontal drags never cross the 2:1
 * verticality gate (cover paging is untouched), and ACTION_DOWN is never
 * claimed (taps, long-presses and sliders keep working).
 */
class QueueGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    /**
     * Fired at most once per gesture, when the upward swipe gate is
     * crossed. When unset the layout is a pure pass-through container.
     */
    var queueSwipeListener: (() -> Unit)? = null

    private val swipeThresholdPx = context.resources.displayMetrics.density * SWIPE_UP_DP

    private var downX = 0f
    private var downY = 0f
    private var swipeClaimed = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> resetGesture(ev)
            MotionEvent.ACTION_MOVE -> maybeClaim(ev)
        }
        return swipeClaimed
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> resetGesture(ev)
            MotionEvent.ACTION_MOVE -> maybeClaim(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeClaimed = false
        }
        return if (swipeClaimed) true else super.onTouchEvent(ev)
    }

    private fun resetGesture(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        swipeClaimed = false
    }

    private fun maybeClaim(ev: MotionEvent) {
        if (swipeClaimed || queueSwipeListener == null || ev.pointerCount != 1) {
            return
        }
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (-dy >= swipeThresholdPx && abs(dy) >= abs(dx) * 2) {
            swipeClaimed = true
            queueSwipeListener?.invoke()
        }
    }

    companion object {
        /** Upward travel (dp) that opens the queue, wherever the swipe starts. */
        private const val SWIPE_UP_DP = 48f
    }
}
