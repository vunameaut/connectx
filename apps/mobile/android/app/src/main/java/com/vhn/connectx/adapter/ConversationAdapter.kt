package com.vhn.connectx.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vhn.connectx.R
import com.vhn.connectx.model.Conversation

class ConversationAdapter(
    private val items: List<Conversation>,
    private val onClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvConvAvatar)
        val tvTitle: TextView = view.findViewById(R.id.tvConvTitle)
        val tvTime: TextView = view.findViewById(R.id.tvConvTime)
        val tvLastMessage: TextView = view.findViewById(R.id.tvConvLastMessage)
        val tvUnreadBadge: TextView = view.findViewById(R.id.tvConvUnreadBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvAvatar.text = item.avatarInitial
        holder.tvTitle.text = item.title
        holder.tvTime.text = item.timestamp
        holder.tvLastMessage.text = item.lastMessage

        if (item.unreadCount > 0) {
            holder.tvUnreadBadge.visibility = View.VISIBLE
            holder.tvUnreadBadge.text = item.unreadCount.toString()
        } else {
            holder.tvUnreadBadge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
