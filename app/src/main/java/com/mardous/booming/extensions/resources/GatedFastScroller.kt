/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mardous.booming.extensions.resources

import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.R
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupTextProvider
import me.zhanghai.android.fastscroll.Predicate

/**
 * While a playlist reorder session is active the drag handle occupies
 * the trailing edge of every row — the same strip where the fast
 * scroller grabs gestures (48dp touch target around track/thumb). The
 * scroller intercepts before child views, so without a gate it steals
 * every drag started on the handle and the scrollbar "takes over".
 * The library ships no disable API, so the touch event listener is
 * gated at the app level instead.
 */
class FastScrollGate(var touchEnabled: Boolean = true)

/**
 * A [FastScroller] and its [FastScrollGate].
 */
class GatedFastScroller(val scroller: FastScroller, val gate: FastScrollGate)

/**
 * A port of the library's RecyclerViewHelper (scroll math is verbatim)
 * whose touch listener defers to a [FastScrollGate]: while gated off
 * the scroller never intercepts, so gestures reach the drag handle.
 */
class GatedRecyclerViewHelper(
    private val view: RecyclerView,
    private val gate: FastScrollGate,
    private val popupTextProvider: PopupTextProvider? = null
) : FastScroller.ViewHelper {

    private val tempRect = Rect()

    override fun addOnPreDrawListener(onPreDraw: Runnable) {
        view.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                onPreDraw.run()
            }
        })
    }

    override fun addOnScrollChangedListener(onScrollChanged: Runnable) {
        view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrollChanged.run()
            }
        })
    }

    override fun addOnTouchEventListener(onTouchEvent: Predicate<MotionEvent>) {
        view.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(
                recyclerView: RecyclerView,
                event: MotionEvent
            ): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    Log.d("BoomingFlowDrag", "scroller saw DOWN, gate=${gate.touchEnabled}")
                }
                if (!gate.touchEnabled) return false
                return onTouchEvent.test(event)
            }

            override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
                onTouchEvent.test(event)
            }
        })
    }

    override fun getScrollRange(): Int {
        val itemCount = itemCount
        if (itemCount == 0) return 0
        val itemHeight = itemHeight
        if (itemHeight == 0) return 0
        return view.paddingTop + itemCount * itemHeight + view.paddingBottom
    }

    override fun getScrollOffset(): Int {
        val firstItemPosition = firstItemPosition
        if (firstItemPosition == RecyclerView.NO_POSITION) return 0
        val itemHeight = itemHeight
        val firstItemTop = firstItemOffset
        return view.paddingTop + firstItemPosition * itemHeight - firstItemTop
    }

    override fun scrollTo(offset: Int) {
        // Stop any scroll in progress for RecyclerView.
        view.stopScroll()
        var offset = offset
        offset -= view.paddingTop
        val itemHeight = itemHeight
        if (itemHeight == 0) return
        // firstItemPosition should be non-negative even if paddingTop is greater than item height.
        val firstItemPosition = Math.max(0, offset / itemHeight)
        val firstItemTop = firstItemPosition * itemHeight - offset
        scrollToPositionWithOffset(firstItemPosition, firstItemTop)
    }

    override fun getPopupText(): CharSequence? {
        var provider = popupTextProvider
        if (provider == null) {
            val adapter = view.adapter
            if (adapter is PopupTextProvider) {
                provider = adapter
            }
        }
        if (provider == null) return null
        val position = firstItemAdapterPosition
        if (position == RecyclerView.NO_POSITION) return null
        return provider.getPopupText(view, position)
    }

    private val itemCount: Int
        get() {
            val linearLayoutManager = verticalLinearLayoutManager ?: return 0
            var itemCount = linearLayoutManager.itemCount
            if (itemCount == 0) return 0
            if (linearLayoutManager is GridLayoutManager) {
                itemCount = (itemCount - 1) / linearLayoutManager.spanCount + 1
            }
            return itemCount
        }

    private val itemHeight: Int
        get() {
            val itemView = view.getChildAt(0) ?: return 0
            view.getDecoratedBoundsWithMargins(itemView, tempRect)
            return tempRect.height()
        }

    private val firstItemPosition: Int
        get() {
            var position = firstItemAdapterPosition
            val linearLayoutManager = verticalLinearLayoutManager
                ?: return RecyclerView.NO_POSITION
            if (linearLayoutManager is GridLayoutManager) {
                position /= linearLayoutManager.spanCount
            }
            return position
        }

    private val firstItemAdapterPosition: Int
        get() {
            if (view.childCount == 0) return RecyclerView.NO_POSITION
            val itemView = view.getChildAt(0) ?: return RecyclerView.NO_POSITION
            val linearLayoutManager = verticalLinearLayoutManager
                ?: return RecyclerView.NO_POSITION
            return linearLayoutManager.getPosition(itemView)
        }

    private val firstItemOffset: Int
        get() {
            val itemView = view.getChildAt(0) ?: return 0
            view.getDecoratedBoundsWithMargins(itemView, tempRect)
            return tempRect.top
        }

    private fun scrollToPositionWithOffset(position: Int, offset: Int) {
        val linearLayoutManager = verticalLinearLayoutManager ?: return
        var position = position
        if (linearLayoutManager is GridLayoutManager) {
            position *= linearLayoutManager.spanCount
        }
        // LinearLayoutManager actually takes offset from paddingTop instead of top of RecyclerView.
        val finalOffset = offset - view.paddingTop
        linearLayoutManager.scrollToPositionWithOffset(position, finalOffset)
    }

    private val verticalLinearLayoutManager: LinearLayoutManager?
        get() {
            val layoutManager = view.layoutManager as? LinearLayoutManager ?: return null
            if (layoutManager.orientation != RecyclerView.VERTICAL) return null
            return layoutManager
        }
}

/**
 * Builds the same MD2 fast scroller as [createFastScroller], but with
 * a [FastScrollGate] wired through a [GatedRecyclerViewHelper].
 */
fun RecyclerView.createGatedFastScroller(disablePopup: Boolean = false): GatedFastScroller {
    val gate = FastScrollGate()
    val helper = GatedRecyclerViewHelper(this, gate)
    val builder = FastScrollerBuilder(this)
        .useMd2Style()
        .setViewHelper(helper)
    ContextCompat.getDrawable(context, R.drawable.scroller_thumb)?.let {
        builder.setThumbDrawable(it)
    }
    ContextCompat.getDrawable(context, R.drawable.scroller_track)?.let {
        builder.setTrackDrawable(it)
    }
    if (disablePopup) {
        builder.setPopupTextProvider { _, _ -> "" }
    }
    return GatedFastScroller(builder.build(), gate)
}
