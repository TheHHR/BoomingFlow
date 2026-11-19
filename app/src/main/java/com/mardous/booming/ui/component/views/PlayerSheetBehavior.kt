package com.mardous.booming.ui.component.views

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * The now playing sheet's behavior. While the sheet holds a nested-scrolling
 * child (the cover lyrics' Compose view), BottomSheetBehavior intercepts EVERY
 * vertical drag that starts outside that child once it passes touch slop - in
 * either direction - so the player's upward queue swipe was cancelled before
 * it could open the queue whenever cover lyrics were showing. An expanded
 * sheet cannot move further up, so upward drags are left to the player;
 * downward drags (swipe to collapse) and nested scrolling are untouched.
 */
class PlayerSheetBehavior<V : View>(context: Context, attrs: AttributeSet?) :
    BottomSheetBehavior<V>(context, attrs) {

    private var downY = 0f

    override fun onInterceptTouchEvent(parent: CoordinatorLayout, child: V, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downY = event.y
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            state == STATE_EXPANDED && event.y < downY
        ) {
            return false
        }
        return super.onInterceptTouchEvent(parent, child, event)
    }
}
