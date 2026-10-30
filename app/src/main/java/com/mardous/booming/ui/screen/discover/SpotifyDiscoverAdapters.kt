package com.mardous.booming.ui.screen.discover

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import com.mardous.booming.R
import com.mardous.booming.core.model.theme.AccentSchemes
import com.mardous.booming.core.PlayerAccentState
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.resources.RowSelectionStyle
import com.mardous.booming.extensions.resources.applyBadgePlayerAccent
import com.mardous.booming.extensions.resources.applyRowSelectionPlayerAccent
import com.mardous.booming.coil.DEFAULT_PLAYLIST_IMAGE
import com.mardous.booming.coil.DEFAULT_SONG_IMAGE
import com.mardous.booming.coil.placeholderDrawableRes
import com.mardous.booming.coil.songImage
import com.mardous.booming.data.model.UnindexedSong
import com.mardous.booming.online.data.spotify.SpotifyHomeItem
import com.mardous.booming.online.data.spotify.SpotifyTrack
import com.mardous.booming.online.data.spotify.thumbnailUrl
import com.mardous.booming.online.download.DownloadRegistry
import com.mardous.booming.ui.component.base.AbsMultiSelectAdapter
import com.mardous.booming.util.Preferences
import coil3.load
import kotlin.properties.Delegates

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
    activity: androidx.fragment.app.FragmentActivity,
    private val onTrackClick: (Int) -> Unit,
    private val onPlayNext: (Int) -> Unit,
    private val onAddToQueue: (Int) -> Unit,
    private val onDownload: (Int) -> Unit = {},
    private val onMultipleItemsAction: (MenuItem, List<com.mardous.booming.data.model.Song>) -> Unit = { _, _ -> }
) : AbsMultiSelectAdapter<SpotifyTrackAdapter.ViewHolder, com.mardous.booming.data.model.Song>(
    activity, R.menu.menu_media_selection
) {

    init {
        // Required: this adapter is wrapped in ConcatAdapters with
        // ISOLATED_STABLE_IDS on the unified Spotify detail pages,
        // which crashes at attach time unless every sub adapter has
        // stable IDs.
        setHasStableIds(true)
    }

    var dataSet: List<SpotifyTrack> by Delegates.observable(emptyList()) { _, _, _ ->
        notifyDataSetChanged()
    }

    var downloadedTrackIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /**
     * Accent from the playlist cover when present (detail page); badge
     * tint uses it over the player accent. 0 = no override.
     */
    var headerAccent: Int = 0
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun getIdentifier(position: Int): com.mardous.booming.data.model.Song? {
        val item = dataSet.getOrNull(position) ?: return null
        if (item.id.isBlank()) return null
        return item.toSong()
    }

    private fun SpotifyTrack.toSong(): com.mardous.booming.data.model.Song =
        UnindexedSong(
            uri = android.net.Uri.parse("https://open.spotify.com"),
            id = id.hashCode().toLong(),
            data = "yt:$id",
            title = name,
            size = 0,
            duration = durationMs.toLong(),
            albumName = album?.name.orEmpty(),
            artistName = artists.joinToString { it.name }
        )

    override fun getName(item: com.mardous.booming.data.model.Song): String {
        return item.title
    }

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<com.mardous.booming.data.model.Song>) {
        onMultipleItemsAction(menuItem, selection)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
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
        val identifier = getIdentifier(position)
        val isChecked = identifier?.let { isChecked(it) } == true
        holder.itemView.isActivated = isChecked
        val rowAccent = if (Preferences.followPlayerColor) {
            PlayerAccentState.accentColor.value
        } else {
            0
        }
        holder.itemView.applyRowSelectionPlayerAccent(rowAccent, isChecked, RowSelectionStyle.Plain)
        holder.title.text = track.name

        val parts = buildList {
            if (track.explicit) add("E")
            val artists = track.artists.joinToString { it.name }
            if (artists.isNotEmpty()) add(artists)
            add(formatDuration(track.durationMs))
        }
        holder.text.text = parts.joinToString(" \u2022 ")

        val isDownloaded = downloadedTrackIds.contains(track.id)
        if (isDownloaded) {
            val localUri = DownloadRegistry.spotifyTrackUri(track.id)
            if (localUri != null) {
                holder.image.songImage(
                    UnindexedSong(
                        uri = android.net.Uri.parse(localUri),
                        id = track.id.hashCode().toLong(),
                        data = "yt:${track.id}",
                        title = track.name,
                        size = 0,
                        duration = track.durationMs.toLong(),
                        albumName = track.album?.name.orEmpty(),
                        artistName = track.artists.joinToString { it.name }
                    )
                )
            } else {
                holder.image.load(track.thumbnailUrl()) {
                    placeholderDrawableRes(context, DEFAULT_SONG_IMAGE)
                }
            }
        } else {
            holder.image.load(track.thumbnailUrl()) {
                placeholderDrawableRes(context, DEFAULT_SONG_IMAGE)
            }
        }

        holder.itemView.setOnClickListener {
            if (isInQuickSelectMode) {
                toggleChecked(position)
            } else {
                onTrackClick(position)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (identifier != null) {
                toggleChecked(position)
            } else {
                holder.menu.callOnClick()
            }
            true
        }
        holder.badge.isVisible = isDownloaded
        val accent = if (headerAccent != 0) {
            headerAccent
        } else if (com.mardous.booming.util.Preferences.followPlayerColor) {
            com.mardous.booming.core.PlayerAccentState.accentColor.value
        } else {
            0
        }
        if (accent != 0) {
            val scheme = AccentSchemes.viewScheme(accent, context.isNightMode)
            holder.badge.applyBadgePlayerAccent(accent, scheme.primary, scheme.onPrimary)
        } else {
            holder.badge.applyBadgePlayerAccent(0, 0, 0)
        }
        holder.menu.isInvisible = isChecked
        holder.menu.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(context, view)
            popup.menu.add(0, 1, 0, context.getString(R.string.play_next_label))
            popup.menu.add(0, 2, 1, context.getString(R.string.add_to_queue_label))
            if (isDownloaded) {
                popup.menu.add(0, 3, 2, context.getString(R.string.download_refresh))
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

    // Stable per-track ids: the lists are deduped by id upstream.
    override fun getItemId(position: Int): Long =
        dataSet.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    private fun formatDuration(durationMs: Int): String {
        val totalSeconds = durationMs / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
