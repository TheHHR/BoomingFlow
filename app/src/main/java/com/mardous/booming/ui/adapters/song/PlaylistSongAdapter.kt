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

package com.mardous.booming.ui.adapters.song

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import androidx.annotation.LayoutRes
import androidx.core.view.isGone
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.R
import com.mardous.booming.data.local.room.PlaylistEntity
import com.mardous.booming.data.mapper.toSongsEntity
import com.mardous.booming.data.model.Song
import com.mardous.booming.ui.ISongCallback
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.util.DragTouchCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class PlaylistSongAdapter(
    activity: FragmentActivity,
    dataSet: List<Song>,
    @LayoutRes
    itemLayoutRes: Int,
    canReorder: Boolean = false,
    callback: ISongCallback? = null,
    private val onOrderChanged: (() -> Unit)? = null
) : SongAdapter(activity, dataSet, itemLayoutRes, null, callback) {

    var canReorder = canReorder
        private set

    /**
     * Synced playlists are rule-owned: individual songs cannot be
     * removed, so the remove actions gray out instead of silently
     * doing nothing.
     */
    var playlistSynced: Boolean = false
        private set

    private val libraryViewModel: LibraryViewModel by activity.viewModel()
    private val mutableDataSet: MutableList<Song>
        get() = dataSet as MutableList

    val isDragging: Boolean
        get() = dragCallback.isDragging

    private val dragCallback = DragTouchCallback(
        canDrag = { canReorder && isInQuickSelectMode },
        onMoved = { from, to ->
            mutableDataSet.add(to, mutableDataSet.removeAt(from))
            notifyItemMoved(from, to)
        },
        // Persist at drag end (queue-style): never rely on onPause.
        onReleased = { onOrderChanged?.invoke() }
    )

    private val itemTouchHelper = ItemTouchHelper(dragCallback)

    fun attachToRecyclerView(recyclerView: RecyclerView?) {
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    override fun createViewHolder(view: View, viewType: Int): SongAdapter.ViewHolder {
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongAdapter.ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        // Reorder session: only in Custom order, only while songs are
        // selected. The overflow button stays in its exact spot but
        // becomes the drag handle: grab it and the row follows the
        // finger, exactly like the queue screen.
        holder.menu?.let { menu ->
            val dragSession = canReorder && isInQuickSelectMode
            if (dragSession) {
                // The base bind hides the overflow button on checked
                // rows; the selected song must show its drag handle too.
                menu.isGone = false
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
    }

    override fun getItemId(position: Int): Long {
        return dataSet[position].id
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val result = super.onCreateActionMode(mode, menu)
        if (playlistSynced) {
            menu.findItem(R.id.action_remove_from_playlist)?.isEnabled = false
        }
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

    fun saveSongs(playlistEntity: PlaylistEntity) {
        if (playlistEntity == PlaylistEntity.Empty)
            return

        activity.lifecycleScope.launch(Dispatchers.IO) {
            libraryViewModel.insertSongs(dataSet.toSongsEntity(playlistEntity))
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setCanReorder(canReorder: Boolean) {
        if (this.canReorder == canReorder) return
        this.canReorder = canReorder
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setPlaylistSynced(synced: Boolean) {
        if (this.playlistSynced == synced) return
        this.playlistSynced = synced
        notifyDataSetChanged()
    }

    inner class ViewHolder internal constructor(itemView: View) : SongAdapter.ViewHolder(itemView) {

        override val songMenuRes: Int
            get() = R.menu.menu_item_playlist_song

        override fun onPrepareSongMenu(menu: Menu) {
            super.onPrepareSongMenu(menu)
            if (playlistSynced) {
                menu.findItem(R.id.action_remove_from_playlist)?.isEnabled = false
            }
        }
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    init {
        this.menuRes = R.menu.menu_playlist_songs_selection
    }
}
