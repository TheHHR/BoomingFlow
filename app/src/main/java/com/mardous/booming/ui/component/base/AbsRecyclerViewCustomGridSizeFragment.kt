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

package com.mardous.booming.ui.component.base

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.R
import com.mardous.booming.core.model.GridViewType
import com.mardous.booming.extensions.isLandscape
import kotlin.math.max

abstract class AbsRecyclerViewCustomGridSizeFragment<Adt : RecyclerView.Adapter<*>, LM : RecyclerView.LayoutManager> :
    AbsRecyclerViewFragment<Adt, LM>(), ViewOptionsDialogHost.Callback {

    protected var gridSize: Int
        get() = getSavedGridSize()
        set(newGridSize) {
            val oldLayoutRes = itemLayoutRes
            saveGridSize(newGridSize)
            // only recreate the adapter and layout manager if the layout currentLayoutRes has changed
            if (oldLayoutRes != itemLayoutRes) {
                invalidateLayoutManager()
                invalidateAdapter()
            } else {
                onGridSizeChanged(isLandscape, newGridSize)
            }
        }

    protected var viewType: GridViewType
        get() = getSavedViewType()
        set(newViewType) {
            saveViewType(newViewType)
            invalidateAdapter()
        }

    protected val isGridMode: Boolean
        get() = gridSize > maxGridSizeForList

    protected open val maxGridSize: Int
        get() = if (isLandscape) {
            resources.getInteger(R.integer.max_columns_land)
        } else resources.getInteger(R.integer.max_columns)

    protected open val maxGridSizeForList: Int
        get() = if (isLandscape) {
            resources.getInteger(R.integer.default_list_columns_land)
        } else resources.getInteger(R.integer.default_list_columns)

    protected open val defaultGridSize: Int
        get() = if (isLandscape) resources.getInteger(R.integer.default_grid_columns_land)
        else resources.getInteger(R.integer.default_grid_columns)

    private var currentLayoutRes = 0

    @get:LayoutRes
    protected open val itemLayoutRes: Int
        get() = if (isGridMode) {
            viewType.layoutRes
        } else R.layout.item_list

    protected val isLandscape: Boolean
        get() = resources.isLandscape

    protected fun notifyLayoutResChanged(@LayoutRes res: Int) {
        currentLayoutRes = res
        applyRecyclerViewPaddingForLayoutRes(recyclerView, currentLayoutRes)
    }

    private fun applyRecyclerViewPaddingForLayoutRes(recyclerView: RecyclerView, @LayoutRes itemLayoutRes: Int) {
        val miniPlayerHeight = libraryViewModel.getMiniPlayerMargin().value?.totalMargin ?: 0
        val padding = GridViewType.getMarginForLayout(itemLayoutRes)
        recyclerView.setPadding(padding, padding, padding, padding + miniPlayerHeight)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyRecyclerViewPaddingForLayoutRes(recyclerView, currentLayoutRes)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateMenu(menu, inflater)
        // View type and grid size are chosen in the accent-following
        // Compose dialog below; the submenu entries stay hidden.
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_view_type || item.itemId == R.id.action_grid_size) {
            ViewOptionsDialog.show(this, viewType, gridSize, maxGridSize)
            return true
        }
        return super.onMenuItemSelected(item)
    }

    override fun onViewTypeSelected(type: GridViewType) {
        viewType = type
    }

    override fun onGridSizeSelected(size: Int) {
        gridSize = size
        toolbar.menu.findItem(R.id.action_view_type)?.isEnabled = isGridMode
    }

    protected abstract fun getSavedViewType(): GridViewType
    protected abstract fun saveViewType(viewType: GridViewType)
    protected abstract fun getSavedGridSize(): Int
    protected abstract fun saveGridSize(newGridSize: Int)
    protected abstract fun onGridSizeChanged(isLand: Boolean, gridColumns: Int)

    private fun setUpGridSizeMenu(gridSizeMenu: Menu) {
        // Legacy overflow-menu entries are hidden; the Compose dialog is
        // the only selector now.
        val allItems = listOf(
            R.id.action_grid_size_1 to 1,
            R.id.action_grid_size_2 to 2,
            R.id.action_grid_size_3 to 3,
            R.id.action_grid_size_4 to 4,
            R.id.action_grid_size_5 to 5,
            R.id.action_grid_size_6 to 6,
            R.id.action_grid_size_7 to 7,
            R.id.action_grid_size_8 to 8
        )
        val selected = max(this.gridSize, 1)
        allItems.forEach { (itemId, size) ->
            gridSizeMenu.findItem(itemId)?.let {
                it.isCheckable = true
                it.isChecked = size == selected
                it.isVisible = size <= max(maxGridSize, 2)
            }
        }
    }

    private fun handleGridSizeMenuItem(item: MenuItem): Boolean {
        var gridSize = 0
        when (item.itemId) {
            R.id.action_grid_size_1 -> gridSize = 1
            R.id.action_grid_size_2 -> gridSize = 2
            R.id.action_grid_size_3 -> gridSize = 3
            R.id.action_grid_size_4 -> gridSize = 4
            R.id.action_grid_size_5 -> gridSize = 5
            R.id.action_grid_size_6 -> gridSize = 6
            R.id.action_grid_size_7 -> gridSize = 7
            R.id.action_grid_size_8 -> gridSize = 8
        }
        if (gridSize > 0) {
            item.isChecked = true
            this.gridSize = gridSize
            this.toolbar.menu
                .findItem(R.id.action_view_type)
                ?.isEnabled = this.isGridMode
            return true
        }
        return false
    }
}
