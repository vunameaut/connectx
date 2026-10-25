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

class CallDetailHistoryAdapter(
    private val items: List<CallHistoryItem>,
    private val onCallItemClick: (CallHistoryItem) -> Unit
) : RecyclerView.Adapter<CallDetailHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivDetailRowIcon)
        val tvType: TextView = view.findViewById(R.id.tvDetailRowType)
        val tvSub: TextView = view.findViewById(R.id.tvDetailRowSub)
        val tvTimestamp: TextView = view.findViewById(R.id.tvDetailRowTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_call_detail_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        val redColor = ContextCompat.getColor(context, R.color.call_red)
        val orangeColor = Color.parseColor("#F97316")
        val greenColor = ContextCompat.getColor(context, R.color.status_online)
        val blueColor = ContextCompat.getColor(context, R.color.colorPrimary)

        val typeName = if (item.isVideo) "video" else "thoại"
        val dirName = if (item.isIncoming) "đến" else "đi"

        when (item.status) {
            "MISSED" -> {
                holder.ivIcon.setImageResource(R.drawable.ic_phone_missed)
                holder.ivIcon.imageTintList = ColorStateList.valueOf(redColor)
                holder.tvType.text = if (item.isIncoming) "Cuộc gọi nhỡ" else "Không nhấc máy"
                holder.tvType.setTextColor(redColor)
                holder.tvSub.text = if (item.isIncoming) "Đối phương gọi nhưng bạn không bắt máy" else "Bạn gọi nhưng đối phương không nghe máy"
            }
            "UNREACHABLE" -> {
                holder.ivIcon.setImageResource(R.drawable.ic_phone_missed)
                holder.ivIcon.imageTintList = ColorStateList.valueOf(orangeColor)
                holder.tvType.text = "Không thể kết nối"
                holder.tvType.setTextColor(orangeColor)
                holder.tvSub.text = "Thuê bao bận hoặc không có kết nối mạng"
            }
            "DECLINED" -> {
                holder.ivIcon.setImageResource(R.drawable.ic_phone_missed)
                holder.ivIcon.imageTintList = ColorStateList.valueOf(redColor)
                holder.tvType.text = "Cuộc gọi bị từ chối"
                holder.tvType.setTextColor(redColor)
                holder.tvSub.text = if (item.isIncoming) "Bạn đã từ chối cuộc gọi" else "Đối phương đã từ chối nhận cuộc gọi"
            }
            else -> {
                // Connected
                holder.tvType.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                holder.tvType.text = "Cuộc gọi $typeName $dirName"

                if (item.isVideo) {
                    holder.ivIcon.setImageResource(R.drawable.ic_videocall)
                    holder.ivIcon.imageTintList = ColorStateList.valueOf(blueColor)
                } else {
                    holder.ivIcon.setImageResource(R.drawable.ic_calls)
                    holder.ivIcon.imageTintList = ColorStateList.valueOf(if (item.isIncoming) greenColor else blueColor)
                }

                if (item.durationSeconds > 0) {
                    val m = item.durationSeconds / 60
                    val s = item.durationSeconds % 60
                    holder.tvSub.text = if (m > 0) "Thời lượng: $m phút $s giây" else "Thời lượng: $s giây"
                } else {
                    holder.tvSub.text = "Đã kết nối thành công"
                }
            }
        }

        holder.tvTimestamp.text = item.timestamp
        holder.itemView.setOnClickListener { onCallItemClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
