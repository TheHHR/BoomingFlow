package com.mardous.booming.ui.screen.discover

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textview.MaterialTextView
import com.mardous.booming.R
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.download.DownloadFormat

class PlaylistDownloadSheet : BottomSheetDialogFragment() {

    private val selected = mutableSetOf<Int>()

    private val playlistFragment: SpotifyPlaylistFragment?
        get() = parentFragment as? SpotifyPlaylistFragment

    private val tracks: List<SpotifyTrack>
        get() = playlistFragment?.downloadableTracks ?: emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.sheet_download_playlist, container, false)

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        val selectAll = view.findViewById<MaterialCheckBox>(R.id.select_all)
        val formatGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.format_group)
        val downloadButton = view.findViewById<MaterialButton>(R.id.download_button)

        val trackList = tracks
        for (i in trackList.indices) selected.add(i)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = TrackPickAdapter(trackList, selected) {
            selectAll.isChecked = selected.size == trackList.size
            updateButton(downloadButton)
        }
        selectAll.isChecked = true
        selectAll.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                for (i in trackList.indices) selected.add(i)
            } else {
                selected.clear()
            }
            recycler.adapter?.notifyDataSetChanged()
            updateButton(downloadButton)
        }

        formatGroup.check(R.id.format_m4a)
        updateButton(downloadButton)
        downloadButton.setOnClickListener {
            val format = if (formatGroup.checkedButtonId == R.id.format_opus) {
                DownloadFormat.OPUS
            } else {
                DownloadFormat.M4A
            }
            val picked = selected.mapNotNull { trackList.getOrNull(it) }
            if (picked.isNotEmpty()) {
                playlistFragment?.startDownload(picked, format)
            }
            dismiss()
        }
    }

    private fun updateButton(button: MaterialButton) {
        button.text = getString(R.string.download_action_count, selected.size)
    }

    private class TrackPickAdapter(
        private val items: List<SpotifyTrack>,
        private val selected: MutableSet<Int>,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<TrackPickAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: MaterialTextView = view.findViewById(R.id.title)
            val subtitle: MaterialTextView = view.findViewById(R.id.subtitle)
            val checkbox: MaterialCheckBox = view.findViewById(R.id.checkbox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_download_pick, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val track = items[position]
            holder.title.text = track.name
            holder.subtitle.text = track.artists.joinToString { it.name }
            holder.checkbox.isChecked = selected.contains(position)
            holder.itemView.setOnClickListener {
                if (selected.contains(position)) selected.remove(position) else selected.add(position)
                holder.checkbox.isChecked = selected.contains(position)
                onSelectionChanged()
            }
        }
    }
}
