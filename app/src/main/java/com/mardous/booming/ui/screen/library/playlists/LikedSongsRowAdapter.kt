package com.mardous.booming.ui.screen.library.playlists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.mardous.booming.R

class LikedSongsRowAdapter(
    private val onClick: () -> Unit
) : RecyclerView.Adapter<LikedSongsRowAdapter.ViewHolder>() {

    private var visible = false
    private var subtitle: String = ""

    fun update(visible: Boolean, subtitle: String) {
        val changed = this.visible != visible || this.subtitle != subtitle
        this.visible = visible
        this.subtitle = subtitle
        if (changed) {
            notifyDataSetChanged()
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: View = itemView.findViewById(R.id.image)
        val title: View = itemView.findViewById(R.id.title)
        val text: View = itemView.findViewById(R.id.text)
        val menu: View = itemView.findViewById(R.id.menu)
        val dragView: View = itemView.findViewById(R.id.drag_view)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.itemView.context
        (holder.image as? android.widget.ImageView)
            ?.setImageResource(R.drawable.ic_favorite_24dp)
        (holder.title as? android.widget.TextView)?.text =
            context.getString(R.string.liked_songs_label)
        (holder.text as? android.widget.TextView)?.text = subtitle
        holder.menu.isVisible = false
        holder.dragView.isVisible = false
        holder.itemView.setOnClickListener { onClick() }
    }

    override fun getItemCount(): Int = if (visible) 1 else 0
}
