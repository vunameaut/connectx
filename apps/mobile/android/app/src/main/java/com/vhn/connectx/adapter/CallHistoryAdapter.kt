package com.vhn.connectx.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vhn.connectx.R
import com.vhn.connectx.model.CallHistoryItem
import com.vhn.connectx.model.GroupedCallHistory

class CallHistoryAdapter(
    private var items: List<GroupedCallHistory>,
    private val onItemClick: (GroupedCallHistory) -> Unit,
    private val onRedial: (CallHistoryItem) -> Unit
) : RecyclerView.Adapter<CallHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvHistoryAvatar)
        val tvName: TextView = view.findViewById(R.id.tvHistoryName)
        val tvCallCountBadge: TextView = view.findViewById(R.id.tvCallCountBadge)
        val tvDetail: TextView = view.findViewById(R.id.tvHistoryDetail)
        val ivCallTypeIcon: ImageView = view.findViewById(R.id.ivCallTypeIcon)
        val btnViewCallDetails: ImageView = view.findViewById(R.id.btnViewCallDetails)
        val btnRedial: ImageView = view.findViewById(R.id.btnRedial)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_call_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = items[position]
        val latest = group.latestCall
        val context = holder.itemView.context

        holder.tvAvatar.text = group.contactName.ifBlank { "U" }.take(1).uppercase()
        holder.tvName.text = group.contactName

        // Group Call Count Badge
        if (group.totalCount > 1) {
            holder.tvCallCountBadge.visibility = View.VISIBLE
            holder.tvCallCountBadge.text = "${group.totalCount}"
        } else {
            holder.tvCallCountBadge.visibility = View.GONE
        }

        // Call status & icon representation
        val redColor = ContextCompat.getColor(context, R.color.call_red)
        val orangeColor = Color.parseColor("#F97316")
        val greenColor = ContextCompat.getColor(context, R.color.status_online)
        val blueColor = ContextCompat.getColor(context, R.color.colorPrimary)
        val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
        val textMuted = ContextCompat.getColor(context, R.color.text_muted)

        when (latest.status) {
            "MISSED" -> {
                holder.ivCallTypeIcon.setImageResource(R.drawable.ic_phone_missed)
                holder.ivCallTypeIcon.imageTintList = ColorStateList.valueOf(redColor)
                holder.tvName.setTextColor(redColor)
                holder.tvDetail.text = "Cuộc gọi nhỡ • ${latest.timestamp}"
                holder.tvDetail.setTextColor(redColor)
            }
            "UNREACHABLE" -> {
                holder.ivCallTypeIcon.setImageResource(R.drawable.ic_phone_missed)
                holder.ivCallTypeIcon.imageTintList = ColorStateList.valueOf(orangeColor)
                holder.tvName.setTextColor(textPrimary)
                holder.tvDetail.text = "Không thể kết nối • ${latest.timestamp}"
                holder.tvDetail.setTextColor(textMuted)
            }
            "DECLINED" -> {
                holder.ivCallTypeIcon.setImageResource(R.drawable.ic_phone_missed)
                holder.ivCallTypeIcon.imageTintList = ColorStateList.valueOf(redColor)
                holder.tvName.setTextColor(textPrimary)
                holder.tvDetail.text = "Đối phương bận/từ chối • ${latest.timestamp}"
                holder.tvDetail.setTextColor(textMuted)
            }
            else -> {
                // Connected / Standard call
                holder.tvName.setTextColor(textPrimary)
                holder.tvDetail.setTextColor(textMuted)

                val dirText = if (latest.isIncoming) "đến" else "đi"
                val typeText = if (latest.isVideo) "video" else "thoại"
                val durationText = if (latest.durationSeconds > 0) {
                    val m = latest.durationSeconds / 60
                    val s = latest.durationSeconds % 60
                    " (${String.format("%02d:%02d", m, s)})"
                } else ""

                if (latest.isVideo) {
                    holder.ivCallTypeIcon.setImageResource(R.drawable.ic_videocall)
                    holder.ivCallTypeIcon.imageTintList = ColorStateList.valueOf(blueColor)
                } else {
                    holder.ivCallTypeIcon.setImageResource(R.drawable.ic_calls)
                    holder.ivCallTypeIcon.imageTintList = ColorStateList.valueOf(if (latest.isIncoming) greenColor else blueColor)
                }
                holder.tvDetail.text = "Gọi $typeText $dirText$durationText • ${latest.timestamp}"
            }
        }

        // Click row or info button to view detailed grouped history
        holder.itemView.setOnClickListener { onItemClick(group) }
        holder.btnViewCallDetails.setOnClickListener { onItemClick(group) }

        // Redial button directly calls latest call type
        holder.btnRedial.setOnClickListener { onRedial(latest) }
    }

    override fun getItemCount(): Int = items.size

    fun setGroupedCalls(newItems: List<GroupedCallHistory>) {
        items = newItems
        notifyDataSetChanged()
    }
}
