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

import android.app.Dialog
import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mardous.booming.R
import com.mardous.booming.core.model.GridViewType
import com.mardous.booming.extensions.withArgs
import com.mardous.booming.ui.component.compose.DialogListItemWithRadio
import com.mardous.booming.ui.theme.BoomingMusicTheme

/**
 * Compose single-choice dialog for library grid view-type + grid size.
 * Replaces the theme-colored overflow check menu items so the selection
 * follows the player accent like every other selection dialog.
 */
class ViewOptionsDialogHost private constructor() {

    interface Callback {
        fun onViewTypeSelected(type: GridViewType)
        fun onGridSizeSelected(size: Int)
    }
}

class ViewOptionsDialog : DialogFragment() {

    private val hostCallback: ViewOptionsDialogHost.Callback?
        get() = parentFragment as? ViewOptionsDialogHost.Callback

    interface Callback {
        fun onViewTypeSelected(type: GridViewType)
        fun onGridSizeSelected(size: Int)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val viewTypes = GridViewType.entries.toList()
        val sizes = (2..(arguments?.getInt(ARG_MAX_COLUMNS) ?: 8)).toList()
        val currentType = GridViewType.entries.firstOrNull {
            it.itemId == (arguments?.getInt(ARG_VIEW_TYPE) ?: 0)
        } ?: GridViewType.Normal
        val currentSize = arguments?.getInt(ARG_GRID_SIZE) ?: 1
        return MaterialAlertDialogBuilder(requireContext())
            .setView(
                ComposeView(requireContext()).apply {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                    )
                    setContent {
                        BoomingMusicTheme {
                            var selectedType by remember { mutableStateOf(currentType) }
                            var selectedSize by remember { mutableStateOf(currentSize) }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.wrapContentHeight()
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight()
                                        .padding(vertical = 24.dp)
                                ) {
                                    items(viewTypes) { type ->
                                        DialogListItemWithRadio(
                                            title = typeLabel(type),
                                            isSelected = type == selectedType,
                                            onClick = {
                                                selectedType = type
                                                (parentFragment as? ViewOptionsDialogHost.Callback)?.onViewTypeSelected(type)
                                                dismiss()
                                            }
                                        )
                                    }
                                    items(sizes) { size ->
                                        DialogListItemWithRadio(
                                            title = getString(R.string.grid_size_x, size),
                                            isSelected = size == selectedSize,
                                            onClick = {
                                                selectedSize = size
                                                (parentFragment as? ViewOptionsDialogHost.Callback)?.onGridSizeSelected(size)
                                                dismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
            .create()
    }

    private fun typeLabel(type: GridViewType): String = getString(
        when (type) {
            GridViewType.Card -> R.string.view_type_card
            GridViewType.ColoredCard -> R.string.view_type_colored_card
            GridViewType.Circle -> R.string.view_type_circle
            GridViewType.Image -> R.string.view_type_image
            else -> R.string.view_type_normal
        }
    )

    companion object {
        private const val ARG_VIEW_TYPE = "view_type"
        private const val ARG_GRID_SIZE = "grid_size"
        private const val ARG_MAX_COLUMNS = "max_columns"

        fun show(
            fragment: Fragment,
            viewType: GridViewType,
            gridSize: Int,
            maxColumns: Int
        ) {
            ViewOptionsDialog().withArgs {
                putInt(ARG_VIEW_TYPE, viewType.itemId)
                putInt(ARG_GRID_SIZE, gridSize)
                putInt(ARG_MAX_COLUMNS, maxColumns)
            }.show(fragment.childFragmentManager, "ViewOptionsDialog")
        }
    }
}

/**
 * Compose single-choice dialog for SortMode selection + descending toggle.
 * Replaces the theme-colored PopupMenu checks so the selection follows
 * the player accent.
 */
class SortOrderDialogFragment : DialogFragment() {

    interface Callback {
        fun onSortOptionSelected(itemId: Int)
        fun onDescendingToggled(descending: Boolean)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val options = arguments?.getStringArrayList(ARG_OPTIONS).orEmpty()
        val selectedIndex = arguments?.getInt(ARG_SELECTED, -1) ?: -1
        val descendingChecked = arguments?.getBoolean(ARG_DESCENDING, false) == true
        val descendingId = arguments?.getInt(ARG_DESCENDING_ID, -1) ?: -1
        return MaterialAlertDialogBuilder(requireContext())
            .setView(
                ComposeView(requireContext()).apply {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                    )
                    setContent {
                        BoomingMusicTheme {
                            var selected by remember { mutableStateOf(selectedIndex) }
                            var descending by remember { mutableStateOf(descendingChecked) }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.wrapContentHeight()
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight()
                                        .padding(vertical = 24.dp)
                                ) {
                                    items(options.indices.toList()) { index ->
                                        DialogListItemWithRadio(
                                            title = options[index],
                                            isSelected = index == selected,
                                            onClick = {
                                                selected = index
                                                (parentFragment as? Callback)?.onSortOptionSelected(
                                                    (arguments?.getIntegerArrayList(ARG_IDS)
                                                        ?: ArrayList())[index]
                                                )
                                                dismiss()
                                            }
                                        )
                                    }
                                    if (descendingId >= 0) {
                                        item {
                                            DialogListItemWithRadio(
                                                title = getString(R.string.sort_order_descending),
                                                isSelected = descending,
                                                onClick = {
                                                    descending = !descending
                                                    (parentFragment as? Callback)?.onDescendingToggled(
                                                        descending
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )
            .create()
    }

    companion object {
        private const val ARG_OPTIONS = "options"
        private const val ARG_IDS = "ids"
        private const val ARG_SELECTED = "selected"
        private const val ARG_DESCENDING = "descending"
        private const val ARG_DESCENDING_ID = "descending_id"

        /**
         * Shows a sort selection dialog. [options] is (itemId, titleRes)
         * pairs; [selectedId] the currently selected item id or -1;
         * pass [descendingId] >= 0 with [descending] to add the
         * descending/ascending toggle row.
         */
        fun show(
            fragment: Fragment,
            options: List<Pair<Int, String>>,
            selectedId: Int,
            descendingId: Int,
            descending: Boolean
        ) {
            SortOrderDialogFragment().withArgs {
                putStringArrayList(ARG_OPTIONS, ArrayList(options.map { it.second }))
                putIntegerArrayList(ARG_IDS, ArrayList(options.map { it.first }))
                putInt(ARG_SELECTED, options.indexOfFirst { it.first == selectedId }.coerceAtLeast(0))
                putInt(ARG_DESCENDING_ID, descendingId)
                putBoolean(ARG_DESCENDING, descending)
            }.show(fragment.childFragmentManager, "SortOrderDialog")
        }
    }
}
