/*
 * Copyright (c) 2024 Christians Martínez Alvarado
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

package com.mardous.booming.util

import android.util.Log
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Vertical drag-to-reorder callback driven imperatively by the row's
 * drag handle (startDrag on touch), so drag initiation cannot race any
 * UI state: handles appear -> grab -> row lifts and follows the finger.
 * Uses bindingAdapterPosition, so it is safe inside a ConcatAdapter.
 */
open class DragTouchCallback(
    private val canDrag: () -> Boolean,
    private val onMoved: (from: Int, to: Int) -> Unit,
    private val onReleased: () -> Unit = {}
) : ItemTouchHelper.Callback() {

    /**
     * True while a drag is in progress. Owners (fragments/adapters) use
     * it to postpone reloads and dataset swaps: a mid-drag update would
     * invalidate every holder (positions -> NO_POSITION), abort the
     * drag and snap the item back to its old spot.
     */
    var isDragging: Boolean = false
        private set

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = if (canDrag()) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
            return false
        }
        onMoved(from, to)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
    }

    override fun isLongPressDragEnabled(): Boolean = false

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        isDragging = actionState == ItemTouchHelper.ACTION_STATE_DRAG
        Log.d(
            "BoomingFlowDrag",
            "select: actionState=$actionState dragging=$isDragging"
        )
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        onReleased()
    }
}
