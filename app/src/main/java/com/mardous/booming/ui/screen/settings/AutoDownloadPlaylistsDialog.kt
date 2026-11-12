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

package com.mardous.booming.ui.screen.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mardous.booming.R
import com.mardous.booming.extensions.create
import com.mardous.booming.extensions.navigation.findActivityNavController
import com.mardous.booming.extensions.navigation.spotifyPlaylistArgs
import com.mardous.booming.extensions.resources.applyPlayerAccent
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import com.mardous.booming.online.download.DownloadRegistry
import com.mardous.booming.online.spotify.SpotifyPlaylistCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Network → Downloads → "Auto-download playlists": lists the playlists
 * set to auto download; tapping one opens its Spotify playlist page.
 * Auto download itself stays managed inside each playlist's download
 * sheet (open-only by design).
 */
class AutoDownloadPlaylistsDialog : DialogFragment() {

    private class Row(val key: String, var name: String)

    private val rows = mutableListOf<Row>()
    private var rowsAdapter: RowsAdapter? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        loadRows()

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.auto_download_playlists_title)
            .setPositiveButton(android.R.string.ok, null)

        if (rows.isEmpty()) {
            builder.setMessage(R.string.auto_download_playlists_none)
        } else {
            val density = resources.displayMetrics.density
            val recycler = RecyclerView(requireContext()).apply {
                layoutManager = LinearLayoutManager(requireContext())
                setPadding(0, (density * 8).toInt(), 0, (density * 8).toInt())
            }
            rowsAdapter = RowsAdapter { row -> openPlaylist(row.key) }
            rowsAdapter?.rows = rows.toList()
            recycler.adapter = rowsAdapter
            builder.setView(recycler)
        }
        return builder.create { it.applyPlayerAccent() }
    }

    /**
     * Name resolution: persisted-at-toggle-time name → per-playlist cache
     * (every auto-download playlist was opened once) → "liked" → raw id.
     * Raw-id rows are rare (pre-name entries with a cleared cache) and get
     * resolved from the network and updated in place.
     */
    private fun loadRows() {
        val context = requireContext()
        for (key in DownloadRegistry.autoDownloadPlaylists(context)) {
            rows += Row(key, displayName(key))
        }
        val unresolved = rows.filter { it.name == it.key }
        if (unresolved.isNotEmpty() && SpotifyPrefs.isConnected(context)) {
            lifecycleScope.launch {
                for (row in unresolved) {
                    val name = withContext(Dispatchers.IO) {
                        runCatching { SpotifyClient.playlist(row.key).getOrNull()?.name }.getOrNull()
                    }
                    if (!name.isNullOrBlank()) {
                        row.name = name
                        rowsAdapter?.rows = rows.toList()
                    }
                }
            }
        }
    }

    private fun displayName(key: String): String {
        val context = requireContext()
        return DownloadRegistry.autoDownloadName(context, key)
            ?: SpotifyPlaylistCache.read(context, key)?.playlist?.name
            ?: if (key == LIKED_KEY) getString(R.string.liked_songs_label) else key
    }

    private fun openPlaylist(key: String) {
        dismiss()
        findActivityNavController(R.id.fragment_container).navigate(
            R.id.nav_spotify_playlist,
            spotifyPlaylistArgs(key)
        )
    }

    @SuppressLint("NotifyDataSetChanged")
    private class RowsAdapter(
        private val onClick: (Row) -> Unit
    ) : RecyclerView.Adapter<RowsAdapter.Holder>() {

        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.title)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context)
                .inflate(R.layout.dialog_item_auto_download, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.title.text = row.name
            holder.itemView.setOnClickListener { onClick(row) }
        }
    }

    private companion object {
        const val LIKED_KEY = "liked"
    }
}
