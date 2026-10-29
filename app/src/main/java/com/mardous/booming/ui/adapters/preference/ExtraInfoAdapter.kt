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

package com.mardous.booming.ui.adapters.preference

import android.content.res.ColorStateList
import androidx.annotation.ColorInt
import com.mardous.booming.core.model.player.MetadataField
import com.mardous.booming.ui.adapters.DraggableItemAdapter

/**
 * @author Christians M. A. (mardous)
 */
class ExtraInfoAdapter(extraInfo: MutableList<MetadataField>) : DraggableItemAdapter<MetadataField>(extraInfo) {

    /** Player-accent tint for the checkboxes; 0 = themed default. */
    @ColorInt
    var accent: Int = 0
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val extraInfo = items[position]

        holder.title.text = holder.title.resources.getString(extraInfo.content.displayNameRes)
        holder.checkBox.isChecked = extraInfo.isEnabled
        holder.checkBox.buttonTintList = if (accent != 0) {
            ColorStateList.valueOf(accent)
        } else {
            null
        }

        holder.itemView.setOnClickListener {
            extraInfo.isEnabled = !extraInfo.isEnabled
            holder.checkBox.isChecked = extraInfo.isEnabled
        }
    }
}