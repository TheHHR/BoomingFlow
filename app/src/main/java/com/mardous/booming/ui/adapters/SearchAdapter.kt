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

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.mardous.booming.R
import com.mardous.booming.core.model.theme.AccentSchemes
import com.mardous.booming.extensions.isNightMode
import com.mardous.booming.extensions.resources.applyBadgePlayerAccent
import com.mardous.booming.extensions.resources.applyRowSelectionPlayerAccent
import com.mardous.booming.coil.DEFAULT_GENRE_IMAGE
import com.mardous.booming.coil.albumImage
import com.mardous.booming.coil.artistImage
import com.mardous.booming.coil.placeholderDrawableRes
import com.mardous.booming.coil.playlistImage
import com.mardous.booming.coil.songImage
import com.mardous.booming.data.local.room.PlaylistWithSongs
import com.mardous.booming.data.model.Album
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.Genre
import com.mardous.booming.data.model.Song
import com.mardous.booming.online.data.spotify.thumbnailUrl
import com.mardous.booming.extensions.media.albumInfo
import com.mardous.booming.extensions.media.artistInfo
import com.mardous.booming.extensions.media.asNumberOfSongs
import com.mardous.booming.extensions.media.displayArtistName
import com.mardous.booming.extensions.media.displayName
import com.mardous.booming.extensions.media.songInfo
import com.mardous.booming.ui.ISearchCallback
import com.mardous.booming.ui.component.base.AbsMultiSelectAdapter
import com.mardous.booming.ui.component.menu.OnClickMenu
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

class SearchAdapter(
    activity: FragmentActivity,
    dataSet: List<Any>,
    private val callback: ISearchCallback? = null,
    private val spotifyCallback: com.mardous.booming.ui.ISpotifySearchCallback? = null
) : AbsMultiSelectAdapter<SearchAdapter.ViewHolder, Song>(activity, R.menu.menu_media_selection) {

    var dataSet by Delegates.observable(dataSet) { _: KProperty<*>, _: List<Any>, _: List<Any> ->
        notifyDataSetChanged()
    }

    /**
     * Live downloaded set from [com.mardous.booming.online.download.DownloadRegistry];
     * setting it rebinds rows so badges follow downloads and accent changes.
     */
    var downloadedSpotifyIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getIdentifier(position: Int): Song? {
        val item = dataSet[position]
        return when (item) {
            is Song -> item
            is com.mardous.booming.online.data.spotify.SpotifyTrack -> item.toSong()
            else -> null
        }
    }

    private fun com.mardous.booming.online.data.spotify.SpotifyTrack.toSong(): Song =
        com.mardous.booming.data.model.UnindexedSong(
            uri = android.net.Uri.parse("https://open.spotify.com"),
            id = id.hashCode().toLong(),
            data = "yt:$id",
            title = name,
            size = 0,
            duration = durationMs.toLong(),
            albumName = album?.name.orEmpty(),
            artistName = artists.joinToString { it.name }
        )

    override fun getName(item: Song): String {
        return item.title
    }

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<Song>) {
        callback?.onMultipleItemAction(menuItem, selection)
    }

    override fun getItemViewType(position: Int): Int {
        val item = dataSet[position]
        if (item is Album) return ALBUM
        if (item is Artist) return ARTIST
        if (item is Song) return SONG
        if (item is PlaylistWithSongs) return PLAYLIST
        if (item is Genre) return GENRE
        if (item is com.mardous.booming.online.data.spotify.SpotifyTrack) return SPOTIFY_TRACK
        if (item is com.mardous.booming.online.data.spotify.SpotifyPlaylist) return SPOTIFY_PLAYLIST
        if (item is com.mardous.booming.online.data.spotify.SpotifyArtist) return SPOTIFY_ARTIST
        if (item is com.mardous.booming.online.data.spotify.SpotifyAlbum) return SPOTIFY_ALBUM
        return HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        if (viewType == HEADER) {
            return ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.sub_header, parent, false), viewType
            )
        }
        val layoutRes = when (viewType) {
            SPOTIFY_TRACK -> R.layout.item_spotify_track
            SPOTIFY_PLAYLIST -> R.layout.item_spotify_search_playlist
            SPOTIFY_ARTIST -> R.layout.item_spotify_artist
            SPOTIFY_ALBUM -> R.layout.item_spotify_album
            else -> R.layout.item_list
        }
        return ViewHolder(LayoutInflater.from(parent.context).inflate(layoutRes, parent, false), viewType)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            ALBUM -> {
                val album = dataSet[position] as Album
                holder.title?.text = album.name.displayArtistName()
                holder.text?.text = album.albumInfo()
                holder.image?.let {
                    it.transitionName = album.id.toString()
                    it.albumImage(album)
                }
            }

            ARTIST -> {
                val artist = dataSet[position] as Artist
                holder.title?.text = artist.displayName()
                holder.text?.text = artist.artistInfo(holder.itemView.context)
                holder.image?.let {
                    it.transitionName = if (artist.isAlbumArtist) artist.name else artist.id.toString()
                    it.artistImage(artist)
                }
            }

            SONG -> {
                val song = dataSet[position] as Song
                val isChecked = isChecked(song)
                holder.isActivated = isChecked
                holder.menu?.isGone = isChecked
                holder.title?.text = song.title
                holder.text?.text = song.songInfo()
                holder.image?.songImage(song)
            }

            PLAYLIST -> {
                val playlist = dataSet[position] as PlaylistWithSongs
                holder.title?.text = playlist.playlistEntity.playlistName
                holder.text?.text = playlist.songCount.asNumberOfSongs(holder.itemView.context)
                holder.image?.playlistImage(playlist)
            }

            GENRE -> {
                val genre = dataSet[position] as Genre
                holder.title?.text = genre.name
                holder.text?.text = genre.songCount.asNumberOfSongs(holder.itemView.context)
                holder.image?.load(genre) {
                    placeholderDrawableRes(holder.image.context, DEFAULT_GENRE_IMAGE)
                }
            }

            SPOTIFY_TRACK -> {
                val track = dataSet[position] as com.mardous.booming.online.data.spotify.SpotifyTrack
                val identifier = getIdentifier(position)
                val isChecked = identifier?.let { isChecked(it) } == true
                holder.isActivated = isChecked
                holder.menu?.isGone = isChecked
                holder.title?.text = track.name
                val parts = buildList {
                    if (track.explicit) add("E")
                    val artists = track.artists.joinToString { it.name }
                    if (artists.isNotEmpty()) add(artists)
                }
                holder.text?.text = parts.joinToString(" • ")
                holder.image?.load(track.thumbnailUrl()) {
                    placeholderDrawableRes(holder.image.context, com.mardous.booming.coil.DEFAULT_SONG_IMAGE)
                }
                holder.badge?.isVisible = downloadedSpotifyIds.contains(track.id)
                val accent = if (com.mardous.booming.util.Preferences.followPlayerColor) {
                    com.mardous.booming.core.PlayerAccentState.accentColor.value
                } else {
                    0
                }
                if (accent != 0) {
                    val scheme = AccentSchemes.viewScheme(accent, holder.itemView.context.isNightMode)
                    holder.badge?.applyBadgePlayerAccent(accent, scheme.primary, scheme.onPrimary)
                } else {
                    holder.badge?.applyBadgePlayerAccent(0, 0, 0)
                }
            }

            SPOTIFY_PLAYLIST -> {
                val playlist = dataSet[position] as com.mardous.booming.online.data.spotify.SpotifyPlaylist
                holder.title?.text = playlist.name
                holder.text?.text = holder.itemView.context.getString(
                    R.string.discover_playlist_subtitle,
                    playlist.owner?.displayName.orEmpty(),
                    playlist.tracks?.total ?: 0
                )
                holder.image?.load(playlist.thumbnailUrl()) {
                    placeholderDrawableRes(holder.image.context, com.mardous.booming.coil.DEFAULT_PLAYLIST_IMAGE)
                }
            }

            SPOTIFY_ARTIST -> {
                val artist = dataSet[position] as com.mardous.booming.online.data.spotify.SpotifyArtist
                holder.title?.text = artist.name
                holder.image?.load(artist.imageUrl) {
                    placeholderDrawableRes(holder.image.context, com.mardous.booming.coil.DEFAULT_SONG_IMAGE)
                }
            }

            SPOTIFY_ALBUM -> {
                val album = dataSet[position] as com.mardous.booming.online.data.spotify.SpotifyAlbum
                holder.title?.text = album.name
                val subtitle = buildString {
                    append(album.artistName)
                    album.year?.let { append(" • $it") }
                }
                holder.text?.text = subtitle
                holder.image?.load(album.imageUrl) {
                    placeholderDrawableRes(holder.image.context, com.mardous.booming.coil.DEFAULT_ALBUM_IMAGE)
                }
            }

            else -> holder.title?.text = dataSet[position].toString()
        }
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    inner class ViewHolder(itemView: View, private val itemViewType: Int) :
        RecyclerView.ViewHolder(itemView), View.OnClickListener, View.OnLongClickListener {

        val image: ImageView? = itemView.findViewById(R.id.image)
        val title: TextView? = itemView.findViewById(R.id.title)
        val text: TextView? = itemView.findViewById(R.id.text)
        val menu: View? = itemView.findViewById(R.id.menu)
        val badge: View? = itemView.findViewById(R.id.badge)

        init {
            itemView.setOnClickListener(this)
            itemView.setOnLongClickListener(this)
            // Ensure long click is enabled
            itemView.isLongClickable = true
        }

        var isActivated: Boolean
            get() = itemView.isActivated
            set(activated) {
                itemView.isActivated = activated
                val accent = if (com.mardous.booming.util.Preferences.followPlayerColor) {
                    com.mardous.booming.core.PlayerAccentState.accentColor.value
                } else {
                    0
                }
                itemView.applyRowSelectionPlayerAccent(
                    accent,
                    activated,
                    com.mardous.booming.extensions.resources.RowSelectionStyle.Plain
                )
            }

        private val sharedElements: Array<Pair<View, String>>
            get() = arrayOf(image!! to image.transitionName)

        override fun onClick(view: View) {
            val item = dataSet[bindingAdapterPosition]
            when (itemViewType) {
                ALBUM -> callback?.albumClick(item as Album, sharedElements)
                ARTIST -> callback?.artistClick(item as Artist, sharedElements)
                SONG -> {
                    val song = item as Song
                    if (isInQuickSelectMode) {
                        toggleChecked(bindingAdapterPosition)
                    } else {
                        callback?.songClick(song, dataSet)
                    }
                }
                GENRE -> callback?.genreClick(item as Genre)
                PLAYLIST -> callback?.playlistClick(item as PlaylistWithSongs)
                SPOTIFY_TRACK -> {
                    val track = item as com.mardous.booming.online.data.spotify.SpotifyTrack
                    if (isInQuickSelectMode) {
                        toggleChecked(bindingAdapterPosition)
                    } else {
                        val tracks = dataSet.filterIsInstance<com.mardous.booming.online.data.spotify.SpotifyTrack>()
                        spotifyCallback?.spotifyTrackClick(track, tracks)
                    }
                }
                SPOTIFY_PLAYLIST -> spotifyCallback?.spotifyPlaylistClick(
                    item as com.mardous.booming.online.data.spotify.SpotifyPlaylist
                )
                SPOTIFY_ARTIST -> spotifyCallback?.spotifyArtistClick(
                    item as com.mardous.booming.online.data.spotify.SpotifyArtist
                )
                SPOTIFY_ALBUM -> spotifyCallback?.spotifyAlbumClick(
                    item as com.mardous.booming.online.data.spotify.SpotifyAlbum
                )
            }
        }

        override fun onLongClick(view: View): Boolean {
            return when (itemViewType) {
                SONG, SPOTIFY_TRACK -> toggleChecked(bindingAdapterPosition)
                else -> menu?.let {
                    it.callOnClick()
                    true
                } ?: false
            }
        }

        private val menuRes = when (itemViewType) {
            SONG -> R.menu.menu_item_song
            ALBUM -> R.menu.menu_item_album
            ARTIST -> R.menu.menu_item_artist
            PLAYLIST -> R.menu.menu_item_playlist
            else -> 0
        }

        init {
            menu?.apply {
                if (itemViewType == SPOTIFY_TRACK) {
                    setOnClickListener { anchor ->
                        val track = dataSet[bindingAdapterPosition]
                            as com.mardous.booming.online.data.spotify.SpotifyTrack
                        val tracks = dataSet.filterIsInstance<com.mardous.booming.online.data.spotify.SpotifyTrack>()
                        val popup = android.widget.PopupMenu(anchor.context, anchor)
                        popup.menu.add(0, 1, 0, anchor.context.getString(R.string.action_play))
                        popup.menu.add(0, 2, 1, anchor.context.getString(R.string.action_queue_next))
                        popup.menu.add(0, 3, 2, anchor.context.getString(R.string.action_add_to_playing_queue))
                        popup.menu.add(0, 4, 3, anchor.context.getString(R.string.action_add_to_playlist))
                        val isDownloaded = com.mardous.booming.online.download.DownloadRegistry
                            .spotifyTrackUri(track.id) != null
                        popup.menu.add(
                            0, 5, 4,
                            anchor.context.getString(
                                if (isDownloaded) R.string.download_refresh else R.string.download_started_label
                            )
                        )
                        track.album?.takeIf { it.id.isNotBlank() }?.let {
                            popup.menu.add(0, 6, 5, anchor.context.getString(R.string.album))
                        }
                        track.artists.firstOrNull { it.id?.isNotBlank() == true }?.let {
                            popup.menu.add(0, 7, 6, anchor.context.getString(R.string.artist))
                        }
                        popup.setOnMenuItemClickListener { menuItem ->
                            when (menuItem.itemId) {
                                1 -> spotifyCallback?.spotifyTrackPlay(track, tracks)
                                2 -> spotifyCallback?.spotifyTrackPlayNext(track)
                                3 -> spotifyCallback?.spotifyTrackEnqueue(track)
                                4 -> spotifyCallback?.spotifyTrackAddToPlaylist(track)
                                5 -> spotifyCallback?.spotifyTrackDownload(track)
                                6 -> spotifyCallback?.spotifyTrackGoToAlbum(track)
                                7 -> spotifyCallback?.spotifyTrackGoToArtist(track)
                            }
                            true
                        }
                        popup.show()
                    }
                    visibility = View.VISIBLE
                } else if (menuRes != 0) {
                    setOnClickListener(object : OnClickMenu() {
                        override val popupMenuRes: Int
                            get() = menuRes

                        override fun onPreparePopup(menu: Menu) {
                            menu.removeItem(R.id.action_play)
                        }

                        override fun onMenuItemClick(item: MenuItem): Boolean {
                            when (val data = dataSet[bindingAdapterPosition]) {
                                is Song -> return callback?.songMenuItemClick(data, item) ?: false
                                is Album -> return callback?.albumMenuItemClick(data, item, sharedElements) ?: false
                                is Artist -> return callback?.artistMenuItemClick(data, item, sharedElements) ?: false
                                is PlaylistWithSongs -> return callback?.playlistMenuItemClick(data, item) ?: false
                            }
                            return false
                        }
                    })
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private const val HEADER = 0
        private const val ALBUM = 1
        private const val ARTIST = 2
        private const val SONG = 3
        private const val PLAYLIST = 4
        private const val GENRE = 5
        private const val SPOTIFY_TRACK = 6
        private const val SPOTIFY_PLAYLIST = 7
        private const val SPOTIFY_ARTIST = 8
        private const val SPOTIFY_ALBUM = 9
    }
}