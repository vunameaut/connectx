package com.vhn.connectx.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vhn.connectx.R
import com.vhn.connectx.model.CallHistoryItem

class CallHistoryAdapter(
    private val items: List<CallHistoryItem>,
    private val onRedial: (CallHistoryItem) -> Unit
) : RecyclerView.Adapter<CallHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvHistoryAvatar)
        val tvName: TextView = view.findViewById(R.id.tvHistoryName)
        val tvDetail: TextView = view.findViewById(R.id.tvHistoryDetail)
        val ivTypeIcon: ImageView = view.findViewById(R.id.ivCallTypeIcon)
        val btnRedial: ImageView = view.findViewById(R.id.btnRedial)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_call_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvAvatar.text = item.contactName.take(1).uppercase()
        holder.tvName.text = item.contactName

        val typeText = if (item.isVideo) "Gọi video" else "Gọi thoại"
        val dirText = if (item.isIncoming) "Đến" else "Đi"
        holder.tvDetail.text = "$typeText $dirText • ${item.timestamp}"

        if (item.isVideo) {
            holder.ivTypeIcon.setImageResource(R.drawable.ic_videocall)
        } else {
            holder.ivTypeIcon.setImageResource(R.drawable.ic_calls)
        }

        holder.btnRedial.setOnClickListener { onRedial(item) }
        holder.itemView.setOnClickListener { onRedial(item) }
    }

    override fun getItemCount(): Int = items.size
}
