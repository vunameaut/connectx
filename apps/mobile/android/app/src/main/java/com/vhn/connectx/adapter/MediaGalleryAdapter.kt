package com.vhn.connectx.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.vhn.connectx.R
import com.vhn.connectx.model.Message

class MediaGalleryAdapter(
    private val mediaMessages: List<Message>
) : RecyclerView.Adapter<MediaGalleryAdapter.MediaViewHolder>() {

    class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView = view.findViewById(R.id.ivMediaThumb)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_thumb, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val msg = mediaMessages[position]
        val path = msg.mediaUrl ?: return
        MessageAdapter.loadImageAsync(holder.ivThumb, path)
        holder.itemView.setOnClickListener {
            MessageAdapter.showFullscreenImage(holder.itemView.context, path)
        }
    }

    override fun getItemCount(): Int = mediaMessages.size
}
