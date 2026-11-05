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
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.R
import com.mardous.booming.coil.playlistImage
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.extensions.isActivated
import com.mardous.booming.extensions.media.asNumberOfSongs
import com.mardous.booming.ui.IPlaylistCallback
import com.mardous.booming.ui.component.base.AbsMultiSelectAdapter
import com.mardous.booming.ui.component.base.MediaEntryViewHolder
import com.mardous.booming.ui.component.menu.OnClickMenu
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.util.DragTouchCallback
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
    private val callback: IPlaylistCallback? = null,
    private val onOrderChanged: (() -> Unit)? = null
) : AbsMultiSelectAdapter<PlaylistAdapter.ViewHolder, PlaylistWithSongs>(activity, R.menu.menu_playlists_selection) {

    var dataSet by Delegates.observable(dataSet) { _: KProperty<*>, _: List<Any>, _: List<Any> ->
        notifyDataSetChanged()
    }

    var canReorder = false
        private set

    private val mutableDataSet: MutableList<PlaylistWithSongs>
        get() = dataSet as MutableList

    val isDragging: Boolean
        get() = itemTouchHelper.dragging

    private val itemTouchHelper = ItemTouchHelper(
        DragTouchCallback(
            canDrag = { canReorder && isInQuickSelectMode },
            onMoved = { from, to ->
                mutableDataSet.add(to, mutableDataSet.removeAt(from))
                notifyItemMoved(from, to)
            },
            // Persist at drag end (queue-style) — a later in-tab reload
            // would otherwise refetch the stale DB order and snap the
            // dragged item back.
            onReleased = { onOrderChanged?.invoke() }
        )
    )

    fun attachToRecyclerView(recyclerView: RecyclerView?) {
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

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
        // Reorder session: list view, Custom order and a selected
        // playlist; the overflow button stays in its exact spot but
        // becomes the drag handle, like the queue screen.
        val dragSession = canReorder && isInQuickSelectMode
        holder.menu?.isGone = isChecked && !dragSession
        holder.menu?.let { menu ->
            if (dragSession) {
                menu.setIconResource(R.drawable.ic_drag_handle_24dp)
                menu.isClickable = false
                menu.setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        itemTouchHelper.startDrag(holder)
                    }
                    true
                }
            } else {
                menu.setIconResource(R.drawable.ic_more_vert_24dp)
                menu.isClickable = true
                menu.setOnTouchListener(null)
            }
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

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val result = super.onCreateActionMode(mode, menu)
        // Rebind all rows so they all swap to the drag handle. POSTED:
        // inside onCreateActionMode the base has not assigned its
        // actionMode field yet, so an inline rebind would render the
        // rows without a drag session (no handles, no listeners).
        mainHandler.post { notifyDataSetChanged() }
        return result
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        super.onDestroyActionMode(mode)
        // The base clears rows before clearing its action mode; rebind
        // again so the handles disappear on every row.
        mainHandler.post { notifyDataSetChanged() }
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    inner class ViewHolder(itemView: View) : MediaEntryViewHolder(itemView) {

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
