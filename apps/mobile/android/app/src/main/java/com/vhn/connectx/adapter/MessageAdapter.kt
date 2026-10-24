package com.vhn.connectx.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vhn.connectx.R
import com.vhn.connectx.model.Message

class MessageAdapter(
    private val messages: MutableList<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_ME = 1
        private const val VIEW_TYPE_OTHER = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isMe) VIEW_TYPE_ME else VIEW_TYPE_OTHER
    }

    class MeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvText: TextView = view.findViewById(R.id.tvMessageTextMe)
        val tvTime: TextView = view.findViewById(R.id.tvMessageTimeMe)
    }

    class OtherViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvMessageOtherAvatar)
        val tvText: TextView = view.findViewById(R.id.tvMessageTextOther)
        val tvTime: TextView = view.findViewById(R.id.tvMessageTimeOther)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ME) {
            val view = inflater.inflate(R.layout.item_message_me, parent, false)
            MeViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_other, parent, false)
            OtherViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is MeViewHolder) {
            holder.tvText.text = msg.text
            holder.tvTime.text = "${msg.timestamp} ✓✓"
        } else if (holder is OtherViewHolder) {
            holder.tvAvatar.text = msg.senderName.take(1).uppercase()
            holder.tvText.text = msg.text
            holder.tvTime.text = msg.timestamp
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}
