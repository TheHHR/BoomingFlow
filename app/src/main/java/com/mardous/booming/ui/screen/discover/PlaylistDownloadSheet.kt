package com.mardous.booming.ui.screen.discover

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
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
import com.mardous.booming.online.download.DownloadRegistry
import com.mardous.booming.online.download.SongDownloader
import org.koin.android.ext.android.inject

class PlaylistDownloadSheet : BottomSheetDialogFragment() {

    private val songDownloader: SongDownloader by inject()

    private val selected = mutableSetOf<Int>()
    private var shownTracks: List<SpotifyTrack> = emptyList()

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
        val hideDownloaded = view.findViewById<MaterialCheckBox>(R.id.hide_downloaded)
        val formatGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.format_group)
        val downloadButton = view.findViewById<MaterialButton>(R.id.download_button)

        val downloadedIds = songDownloader.downloadedSpotifyTrackIds()
        val playlistKey = playlistFragment?.playlistKey
        val autoDownload = view.findViewById<MaterialCheckBox>(R.id.auto_download)
        if (playlistKey != null) {
            autoDownload.isChecked = DownloadRegistry.autoDownload(requireContext(), playlistKey)
            autoDownload.setOnCheckedChangeListener { _, checked ->
                DownloadRegistry.setAutoDownload(requireContext(), playlistKey, checked)
                if (checked) {
                    playlistFragment?.startAutoDownload()
                }
            }
        } else {
            autoDownload.isVisible = false
        }
        hideDownloaded.isChecked = DownloadRegistry.hideDownloaded(requireContext())
        hideDownloaded.setOnCheckedChangeListener { _, checked ->
            rebuildList(recycler, selectAll, downloadButton, downloadedIds, checked)
        }

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = TrackPickAdapter(
            selected = selected,
            shownTracks = { shownTracks },
            onSelectionChanged = {
                selectAll.isChecked = shownTracks.isNotEmpty() && selected.size == shownTracks.size
                updateButton(downloadButton)
            }
        )

        selectAll.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                for (i in shownTracks.indices) selected.add(i)
            } else {
                selected.clear()
            }
            recycler.adapter?.notifyDataSetChanged()
            updateButton(downloadButton)
        }

        formatGroup.check(R.id.format_m4a)
        rebuildList(
            recycler, selectAll, downloadButton, downloadedIds,
            DownloadRegistry.hideDownloaded(requireContext())
        )

        downloadButton.setOnClickListener {
            val format = if (formatGroup.checkedButtonId == R.id.format_opus) {
                DownloadFormat.OPUS
            } else {
                DownloadFormat.M4A
            }
            val picked = selected.mapNotNull { shownTracks.getOrNull(it) }
            if (picked.isNotEmpty()) {
                playlistFragment?.startDownload(picked, format)
            }
            dismiss()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun rebuildList(
        recycler: RecyclerView,
        selectAll: MaterialCheckBox,
        downloadButton: MaterialButton,
        downloadedIds: Set<String>,
        hide: Boolean
    ) {
        DownloadRegistry.setHideDownloaded(requireContext(), hide)
        shownTracks = if (hide) {
            tracks.filterNot { downloadedIds.contains(it.id) }
        } else {
            tracks
        }
        selected.clear()
        for (i in shownTracks.indices) selected.add(i)
        selectAll.isChecked = shownTracks.isNotEmpty()
        recycler.adapter?.notifyDataSetChanged()
        updateButton(downloadButton)
    }

    private fun updateButton(button: MaterialButton) {
        button.text = getString(R.string.download_action_count, selected.size)
    }

    private class TrackPickAdapter(
        private val selected: MutableSet<Int>,
        private val shownTracks: () -> List<SpotifyTrack>,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<TrackPickAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: MaterialTextView = view.findViewById(R.id.title)
            val subtitle: MaterialTextView = view.findViewById(R.id.subtitle)
            val checkbox: MaterialCheckBox = view.findViewById(R.id.checkbox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_download_pick, parent, false))

        override fun getItemCount(): Int = shownTracks().size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val track = shownTracks()[position]
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
