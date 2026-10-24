package com.vhn.connectx.adapter

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.vhn.connectx.R
import com.vhn.connectx.model.Message
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors

class MessageAdapter(
    private val messages: MutableList<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_ME = 1
        private const val VIEW_TYPE_OTHER = 2
        private val imageExecutor = Executors.newFixedThreadPool(4)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val okHttpClient = OkHttpClient()
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isMe) VIEW_TYPE_ME else VIEW_TYPE_OTHER
    }

    class MeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardImage: CardView = view.findViewById(R.id.cardMessageImageMe)
        val ivImage: ImageView = view.findViewById(R.id.ivMessageImageMe)
        val tvText: TextView = view.findViewById(R.id.tvMessageTextMe)
        val tvTime: TextView = view.findViewById(R.id.tvMessageTimeMe)
    }

    class OtherViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tvMessageOtherAvatar)
        val cardImage: CardView = view.findViewById(R.id.cardMessageImageOther)
        val ivImage: ImageView = view.findViewById(R.id.ivMessageImageOther)
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
        val imageUrl = msg.mediaUrl ?: extractImageUrl(msg.text)

        if (holder is MeViewHolder) {
            holder.tvTime.text = "${msg.timestamp} ✓✓"
            bindImage(holder.cardImage, holder.ivImage, holder.tvText, imageUrl, msg.text)
        } else if (holder is OtherViewHolder) {
            holder.tvAvatar.text = msg.senderName.ifBlank { "U" }.take(1).uppercase()
            holder.tvTime.text = msg.timestamp
            bindImage(holder.cardImage, holder.ivImage, holder.tvText, imageUrl, msg.text)
        }
    }

    private fun bindImage(
        card: CardView,
        imageView: ImageView,
        textView: TextView,
        imageUrl: String?,
        originalText: String
    ) {
        if (!imageUrl.isNullOrBlank()) {
            card.visibility = View.VISIBLE
            loadImageAsync(imageView, imageUrl)
            // If the text is just the image link, hide the redundant link text
            if (originalText.startsWith("📷 [Hình ảnh]") || originalText == imageUrl) {
                textView.visibility = View.GONE
            } else {
                textView.visibility = View.VISIBLE
                textView.text = originalText
            }

            card.setOnClickListener {
                showFullscreenImage(imageView.context, imageUrl)
            }
        } else {
            card.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = originalText
        }
    }

    private fun extractImageUrl(text: String): String? {
        if (text.startsWith("📷 [Hình ảnh]: ")) {
            return text.removePrefix("📷 [Hình ảnh]: ").trim()
        }
        if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("content://") || text.startsWith("/")) {
            if (text.endsWith(".jpg") || text.endsWith(".png") || text.endsWith(".jpeg") || text.contains("cloudinary")) {
                return text
            }
        }
        return null
    }

    private fun loadImageAsync(imageView: ImageView, path: String) {
        imageView.setImageDrawable(null)
        imageView.tag = path

        imageExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                if (path.startsWith("data:image")) {
                    val base64Data = path.substringAfter("base64,")
                    val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                    bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                } else if (path.startsWith("content://")) {
                    val uri = Uri.parse(path)
                    imageView.context.contentResolver.openInputStream(uri)?.use { stream ->
                        bitmap = BitmapFactory.decodeStream(stream)
                    }
                } else if (path.startsWith("/")) {
                    val file = File(path)
                    if (file.exists()) {
                        bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    }
                } else if (path.startsWith("http://") || path.startsWith("https://")) {
                    val req = Request.Builder().url(path).build()
                    val response = okHttpClient.newCall(req).execute()
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { stream ->
                            bitmap = BitmapFactory.decodeStream(stream)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (bitmap != null) {
                mainHandler.post {
                    if (imageView.tag == path) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun showFullscreenImage(context: android.content.Context, path: String) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val fullImageView = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(fullImageView)
        loadImageAsync(fullImageView, path)
        dialog.show()
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun setMessages(newMessages: List<Message>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }
}
