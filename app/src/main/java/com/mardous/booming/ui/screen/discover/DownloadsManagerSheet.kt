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
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.mardous.booming.R
import com.mardous.booming.online.download.DownloadProgress
import com.mardous.booming.online.download.SongDownloader
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import androidx.lifecycle.lifecycleScope

class DownloadsManagerSheet : BottomSheetDialogFragment() {

    private val songDownloader: SongDownloader by inject()

    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: MaterialTextView
    private lateinit var adapter: DownloadsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.sheet_downloads_manager, container, false)

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recycler)
        emptyText = view.findViewById(R.id.empty_text)

        adapter = DownloadsAdapter(
            onDelete = { videoId ->
                songDownloader.deleteDownloaded(videoId)
                refresh()
            }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            songDownloader.progress.collect { refresh() }
        }
        refresh()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        if (!isAdded) return
        val active = songDownloader.progress.value.values
            .sortedWith(compareBy({ it.state != DownloadProgress.State.RUNNING }, { it.title }))
        val completed = songDownloader.downloadedVideoIds
            .mapNotNull { videoId ->
                songDownloader.downloadedDisplayName(videoId)?.let { Row.Completed(videoId, it) }
            }
        adapter.rows = active.map { Row.Active(it) } + completed
        adapter.notifyDataSetChanged()
        emptyText.isVisible = adapter.rows.isEmpty()
        recycler.isVisible = adapter.rows.isNotEmpty()
    }

    private sealed interface Row {
        data class Active(val progress: DownloadProgress) : Row
        data class Completed(val videoId: String, val title: String) : Row
    }

    private class DownloadsAdapter(
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<DownloadsAdapter.Holder>() {

        var rows: List<Row> = emptyList()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: MaterialTextView = view.findViewById(R.id.title)
            val percent: MaterialTextView = view.findViewById(R.id.percent)
            val progress: LinearProgressIndicator = view.findViewById(R.id.progress)
            val delete: View = view.findViewById(R.id.delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_download_progress, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is Row.Active -> {
                    holder.title.text = row.progress.title
                    holder.delete.isVisible = false
                    when (row.progress.state) {
                        DownloadProgress.State.QUEUED -> {
                            holder.progress.isIndeterminate = true
                            holder.percent.setText(R.string.download_state_queued)
                        }
                        DownloadProgress.State.FAILED -> {
                            holder.progress.isIndeterminate = false
                            holder.progress.setProgressCompat(100, false)
                            holder.percent.setText(R.string.download_state_failed)
                        }
                        else -> {
                            holder.progress.isIndeterminate = false
                            holder.progress.setProgressCompat(row.progress.percent, true)
                            holder.percent.text = "${row.progress.percent}%"
                        }
                    }
                }
                is Row.Completed -> {
                    holder.title.text = row.title
                    holder.progress.isIndeterminate = false
                    holder.progress.setProgressCompat(100, false)
                    holder.percent.setText(R.string.download_state_done)
                    holder.delete.isVisible = true
                    holder.delete.setOnClickListener { onDelete(row.videoId) }
                }
            }
        }
    }
}
