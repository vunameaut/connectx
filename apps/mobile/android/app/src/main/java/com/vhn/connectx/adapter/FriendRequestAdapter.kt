package com.vhn.connectx.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.vhn.connectx.R
import com.vhn.connectx.model.FriendRequest

class FriendRequestAdapter(
    private val items: List<FriendRequest>,
    private val onAccept: (FriendRequest) -> Unit,
    private val onReject: (FriendRequest) -> Unit
) : RecyclerView.Adapter<FriendRequestAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvReqAvatar)
        val tvSenderName: TextView = view.findViewById(R.id.tvReqSenderName)
        val tvSenderEmail: TextView = view.findViewById(R.id.tvReqSenderEmail)
        val tvTimestamp: TextView = view.findViewById(R.id.tvReqTimestamp)
        val btnAccept: MaterialButton = view.findViewById(R.id.btnAcceptRequest)
        val btnReject: MaterialButton = view.findViewById(R.id.btnRejectRequest)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_friend_request, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val initial = item.senderName.ifBlank { item.senderEmail.substringBefore("@") }.ifBlank { "U" }.take(1).uppercase()
        holder.tvAvatar.text = initial
        holder.tvSenderName.text = item.senderName.ifBlank { item.senderEmail.substringBefore("@") }
        holder.tvSenderEmail.text = item.senderEmail
        holder.tvTimestamp.text = item.timestamp

        holder.btnAccept.setOnClickListener { onAccept(item) }
        holder.btnReject.setOnClickListener { onReject(item) }
    }

    override fun getItemCount(): Int = items.size
}
