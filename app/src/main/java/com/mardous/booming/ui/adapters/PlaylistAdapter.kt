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

package com.mardous.booming.ui.adapters

import android.annotation.SuppressLint
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemAdapter
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemState
import com.h6ah4i.android.widget.advrecyclerview.draggable.DraggableItemViewHolder
import com.h6ah4i.android.widget.advrecyclerview.draggable.ItemDraggableRange
import com.h6ah4i.android.widget.advrecyclerview.draggable.annotation.DraggableItemStateFlags
import com.mardous.booming.R
import com.mardous.booming.coil.playlistImage
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.extensions.isActivated
import com.mardous.booming.extensions.media.asNumberOfSongs
import com.mardous.booming.extensions.resources.hitTest
import com.mardous.booming.ui.IPlaylistCallback
import com.mardous.booming.ui.component.base.AbsMultiSelectAdapter
import com.mardous.booming.ui.component.base.MediaEntryViewHolder
import com.mardous.booming.ui.component.menu.OnClickMenu
import com.mardous.booming.ui.screen.player.PlayerViewModel
import kotlin.properties.Delegates
import kotlin.reflect.KProperty


/**
 * @author Christians M. A. (mardous)
 */
class PlaylistAdapter(
    activity: AppCompatActivity,
    dataSet: List<PlaylistWithSongs>,
    @LayoutRes
    private val itemLayoutRes: Int,
    private val callback: IPlaylistCallback? = null
) : AbsMultiSelectAdapter<PlaylistAdapter.ViewHolder, PlaylistWithSongs>(activity, R.menu.menu_playlists_selection),
    DraggableItemAdapter<PlaylistAdapter.ViewHolder> {

    var dataSet by Delegates.observable(dataSet) { _: KProperty<*>, _: List<Any>, _: List<Any> ->
        notifyDataSetChanged()
    }

    var canReorder = false
        private set

    private val mutableDataSet: MutableList<PlaylistWithSongs>
        get() = dataSet as MutableList

    init {
        setHasStableIds(true)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setCanReorder(canReorder: Boolean) {
        if (this.canReorder == canReorder) return
        this.canReorder = canReorder
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return LayoutInflater.from(parent.context).inflate(itemLayoutRes, parent, false).let { itemView ->
            createViewHolder(itemView)
        }
    }

    private fun createViewHolder(view: View): ViewHolder {
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = dataSet[position]
        val isChecked = isChecked(playlist)
        holder.isActivated = isChecked
        holder.menu?.isGone = isChecked
        // Reorder session: list view, Custom order and a selected song;
        // rows swap the overflow menu for the drag handle.
        val dragSession = canReorder && isInQuickSelectMode
        holder.dragView?.isVisible = dragSession
        if (dragSession) {
            holder.menu?.isGone = true
        }
        holder.title?.text = playlist.playlistEntity.playlistName
        if (holder.text != null) {
            holder.text.text = playlist.songCount.asNumberOfSongs(holder.itemView.context)
        }
        if (holder.imageContainer != null) {
            holder.imageContainer.transitionName = playlist.playlistEntity.playlistName
        } else {
            holder.image?.transitionName = playlist.playlistEntity.playlistName
        }
        holder.image?.playlistImage(playlist)
    }

    override fun getItemId(position: Int): Long {
        return dataSet[position].playlistEntity.playListId
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    override fun getIdentifier(position: Int): PlaylistWithSongs {
        return dataSet[position]
    }

    override fun getName(item: PlaylistWithSongs): String {
        return item.playlistEntity.playlistName
    }

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<PlaylistWithSongs>) {
        callback?.playlistsMenuItemClick(selection, menuItem)
    }

    override fun onCheckCanStartDrag(holder: ViewHolder, position: Int, x: Int, y: Int): Boolean {
        if (!canReorder || !isInQuickSelectMode) {
            return false
        }
        return (holder.dragView?.hitTest(x, y) ?: false)
    }

    override fun onGetItemDraggableRange(holder: ViewHolder, position: Int): ItemDraggableRange? {
        return null
    }

    override fun onMoveItem(fromPosition: Int, toPosition: Int) {
        mutableDataSet.add(toPosition, mutableDataSet.removeAt(fromPosition))
    }

    override fun onCheckCanDrop(draggingPosition: Int, dropPosition: Int): Boolean {
        return true
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onItemDragStarted(position: Int) {
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onItemDragFinished(fromPosition: Int, toPosition: Int, result: Boolean) {
        notifyDataSetChanged()
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val result = super.onCreateActionMode(mode, menu)
        // Rebind all rows so they all swap to the drag handle.
        notifyDataSetChanged()
        return result
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onDestroyActionMode(mode: ActionMode) {
        super.onDestroyActionMode(mode)
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView),
        DraggableItemViewHolder {

        private val mDragItemState: DraggableItemState = DraggableItemState()

        override fun getDragState(): DraggableItemState {
            return mDragItemState
        }

        @DraggableItemStateFlags
        override fun getDragStateFlags(): Int {
            return mDragItemState.flags
        }

        override fun setDragStateFlags(flags: Int) {
            mDragItemState.flags = flags
        }

        init {
            menu?.setOnClickListener(object : OnClickMenu() {
                override val popupMenuRes: Int
                    get() = R.menu.menu_item_playlist

                override fun onMenuItemClick(item: MenuItem): Boolean =
                    playlist?.let { callback?.playlistMenuItemClick(it, item) } ?: false
            })
            play?.setOnClickListener {
                playlist?.let { getViewModel<PlayerViewModel>()?.openPlaylist(it.playlistEntity) }
            }
        }

        private val playlist: PlaylistWithSongs?
            get() = dataSet.getOrNull(bindingAdapterPosition)

        override fun onClick(view: View) {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            if (isInQuickSelectMode) {
                toggleChecked(position)
            } else {
                val playlist = playlist ?: return
                callback?.playlistClick(playlist)
            }
        }

        override fun onLongClick(view: View): Boolean {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return false
            return toggleChecked(position)
        }
    }
}
