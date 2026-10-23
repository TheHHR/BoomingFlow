package com.mardous.booming.ui.screen.discover

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import com.mardous.booming.R
import com.mardous.booming.coil.DEFAULT_PLAYLIST_IMAGE
import com.mardous.booming.coil.DEFAULT_SONG_IMAGE
import com.mardous.booming.coil.placeholderDrawableRes
import com.mardous.booming.online.data.spotify.SpotifyHomeItem
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.data.spotify.thumbnailUrl
import coil3.load
import kotlin.properties.Delegates

class GreetingAdapter(private var text: String) : RecyclerView.Adapter<GreetingAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val greeting: MaterialTextView = itemView.findViewById(R.id.greeting)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_spotify_greeting, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.greeting.text = text
    }

    override fun getItemCount(): Int = 1
}

class SpotifyCardAdapter(
    private val onPlaylistClick: (String) -> Unit
) : RecyclerView.Adapter<SpotifyCardAdapter.ViewHolder>() {

    var dataSet: List<SpotifyHomeItem.Playlist> by Delegates.observable(emptyList()) { _, _, _ ->
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ShapeableImageView = itemView.findViewById(R.id.image)
        val title: MaterialTextView = itemView.findViewById(R.id.title)
        val text: MaterialTextView = itemView.findViewById(R.id.text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_spotify_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataSet[position]
        holder.title.text = item.name
        holder.text.text = item.ownerName ?: "Playlist"
        holder.image.load(item.imageUrl) {
            placeholderDrawableRes(holder.itemView.context, DEFAULT_PLAYLIST_IMAGE)
        }
        holder.itemView.setOnClickListener { onPlaylistClick(item.id) }
    }

    override fun getItemCount(): Int = dataSet.size
}

class SpotifyTrackAdapter(
    private val onTrackClick: (Int) -> Unit,
    private val onPlayNext: (Int) -> Unit,
    private val onAddToQueue: (Int) -> Unit,
    private val onDownload: (Int) -> Unit = {}
) : RecyclerView.Adapter<SpotifyTrackAdapter.ViewHolder>() {

    var dataSet: List<SpotifyTrack> by Delegates.observable(emptyList()) { _, _, _ ->
        notifyDataSetChanged()
    }

    var downloadedTrackIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val index: MaterialTextView = itemView.findViewById(R.id.index)
        val image: ShapeableImageView = itemView.findViewById(R.id.image)
        val badge: ShapeableImageView = itemView.findViewById(R.id.badge)
        val title: MaterialTextView = itemView.findViewById(R.id.title)
        val text: MaterialTextView = itemView.findViewById(R.id.text)
        val menu: View = itemView.findViewById(R.id.menu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_spotify_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = dataSet[position]
        val context = holder.itemView.context
        holder.index.text = (position + 1).toString()
        holder.title.text = track.name

        val parts = buildList {
            if (track.explicit) add("E")
            val artists = track.artists.joinToString { it.name }
            if (artists.isNotEmpty()) add(artists)
            add(formatDuration(track.durationMs))
        }
        holder.text.text = parts.joinToString(" \u2022 ")

        holder.image.load(track.thumbnailUrl()) {
            placeholderDrawableRes(context, DEFAULT_SONG_IMAGE)
        }

        holder.itemView.setOnClickListener { onTrackClick(position) }
        val isDownloaded = downloadedTrackIds.contains(track.id)
        holder.badge.isVisible = isDownloaded
        holder.menu.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(context, view)
            popup.menu.add(0, 1, 0, context.getString(R.string.play_next_label))
            popup.menu.add(0, 2, 1, context.getString(R.string.add_to_queue_label))
            if (isDownloaded) {
                val item = popup.menu.add(0, 3, 2, context.getString(R.string.download_already_downloaded))
                item.isEnabled = false
            } else {
                popup.menu.add(0, 3, 2, context.getString(R.string.download_started_label))
            }
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> onPlayNext(position)
                    2 -> onAddToQueue(position)
                    3 -> onDownload(position)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = dataSet.size

    private fun formatDuration(durationMs: Int): String {
        val totalSeconds = durationMs / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
