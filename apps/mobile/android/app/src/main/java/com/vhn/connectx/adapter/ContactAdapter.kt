package com.vhn.connectx.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vhn.connectx.R
import com.vhn.connectx.model.User

class ContactAdapter(
    private var items: List<User>,
    private val onChatClick: (User) -> Unit,
    private val onCallClick: (User) -> Unit,
    private val onDeleteClick: (User) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvContactAvatar)
        val tvName: TextView = view.findViewById(R.id.tvContactName)
        val tvEmail: TextView = view.findViewById(R.id.tvContactEmail)
        val btnQuickChat: View = view.findViewById(R.id.btnQuickChat)
        val btnQuickCall: View = view.findViewById(R.id.btnQuickCall)
        val btnDeleteContact: ImageView = view.findViewById(R.id.btnDeleteContact)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvAvatar.text = item.displayName.ifBlank { "U" }.take(1).uppercase()
        holder.tvName.text = item.displayName
        holder.tvEmail.text = item.email

        holder.btnQuickChat.setOnClickListener { onChatClick(item) }
        holder.btnQuickCall.setOnClickListener { onCallClick(item) }
        holder.btnDeleteContact.setOnClickListener { onDeleteClick(item) }

        holder.itemView.setOnClickListener { onChatClick(item) }
        holder.itemView.setOnLongClickListener {
            onDeleteClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    fun setContacts(newItems: List<User>) {
        items = newItems
        notifyDataSetChanged()
    }
}
