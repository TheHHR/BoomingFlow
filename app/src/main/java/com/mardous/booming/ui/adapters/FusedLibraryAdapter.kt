package com.mardous.booming.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.mardous.booming.R
import com.mardous.booming.coil.DEFAULT_PLAYLIST_IMAGE
import com.mardous.booming.coil.placeholderDrawableRes
import com.mardous.booming.online.data.spotify.SpotifyHomeItem
import coil3.load

/**
 * One-row shelf: a section title above a horizontally scrolling row of
 * Spotify cards. Cards inflate [R.layout.item_spotify_card] (156dp),
 * same look as the Discover page shelves.
 */
class SpotifyShelfAdapter(
    private val onItemClickListener: (SpotifyHomeItem) -> Unit
) : RecyclerView.Adapter<SpotifyShelfAdapter.ShelfViewHolder>() {

    var items: List<SpotifyHomeItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class ShelfViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.title)
        val shelf: RecyclerView = itemView.findViewById(R.id.shelf)
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {

        inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val image: ShapeableImageView = itemView.findViewById(R.id.image)
            val title: TextView = itemView.findViewById(R.id.title)
            val text: TextView = itemView.findViewById(R.id.text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder =
            CardViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_spotify_card, parent, false)
            )

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context
            when (item) {
                is SpotifyHomeItem.Playlist -> {
                    holder.title.text = item.name
                    holder.text.text = item.ownerName ?: "Playlist"
                }
                is SpotifyHomeItem.Album -> {
                    holder.title.text = item.name
                    holder.text.text = item.artists.joinToString { it.name }.ifEmpty { "Album" }
                }
                is SpotifyHomeItem.Artist -> {
                    holder.title.text = item.name
                    holder.text.text = "Artist"
                }
            }
            holder.image.load(item.imageUrl) {
                placeholderDrawableRes(context, DEFAULT_PLAYLIST_IMAGE)
            }
            holder.itemView.setOnClickListener { onItemClickListener(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private val cardAdapter = CardAdapter()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShelfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_spotify_shelf, parent, false)
        return ShelfViewHolder(view)
    }

    override fun onBindViewHolder(holder: ShelfViewHolder, position: Int) {
        if (holder.shelf.layoutManager == null) {
            holder.shelf.layoutManager =
                LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
        }
        if (holder.shelf.adapter == null) {
            holder.shelf.adapter = cardAdapter
        }
        cardAdapter.notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 0 else 1
}

/**
 * Fused library page adapter: the local grid adapter first, then a
 * "Spotify" section header and a horizontally scrolling card shelf.
 * The header row is hidden while the shelf has no items, so logged-out
 * and offline users just see the plain local page.
 */
class FusedLibraryAdapter(
    private val headerTitle: String,
    val localAdapter: RecyclerView.Adapter<*>,
    private val onSpotifyItemClick: (SpotifyHomeItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val headerAdapter = SectionHeaderAdapter(headerTitle)
    private val shelfAdapter = SpotifyShelfAdapter(onSpotifyItemClick)

    var spotifyItems: List<SpotifyHomeItem>
        get() = shelfAdapter.items
        set(value) {
            shelfAdapter.items = value
            headerAdapter.setVisible(value.isNotEmpty())
            notifyDataSetChanged()
        }

    private val concatAdapter = ConcatAdapter(
        ConcatAdapter.Config.Builder()
            .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
            .build(),
        localAdapter, headerAdapter, shelfAdapter
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        concatAdapter.onCreateViewHolder(parent, viewType)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        concatAdapter.onBindViewHolder(holder, position)
    }

    override fun getItemViewType(position: Int): Int = concatAdapter.getItemViewType(position)

    override fun getItemCount(): Int = concatAdapter.itemCount

    override fun getItemId(position: Int): Long = concatAdapter.getItemId(position)

    /**
     * Span lookup for GridLayoutManager use: local rows take one cell,
     * the Spotify header/shelf rows span the full width.
     */
    fun spanSizeFor(position: Int, spanCount: Int): Int {
        val wrapped = concatAdapter.getWrappedAdapterAndPosition(position)
        return if (wrapped.first === localAdapter) 1 else spanCount
    }
}
