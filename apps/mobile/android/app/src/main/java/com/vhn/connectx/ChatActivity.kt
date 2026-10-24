package com.vhn.connectx

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.vhn.connectx.adapter.MessageAdapter
import com.vhn.connectx.data.AppDataStore
import com.vhn.connectx.model.CallHistoryItem
import com.vhn.connectx.model.Message
import com.vhn.connectx.model.SessionManager
import com.vhn.connectx.net.ApiClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var appDataStore: AppDataStore
    private lateinit var rvMessages: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private val messages = mutableListOf<Message>()

    private lateinit var btnBack: ImageView
    private lateinit var tvChatAvatar: TextView
    private lateinit var tvChatContactName: TextView
    private lateinit var btnChatVoiceCall: ImageView
    private lateinit var btnChatVideoCall: ImageView
    private lateinit var etMessageInput: EditText
    private lateinit var btnSendMessage: FloatingActionButton
    private lateinit var btnAttachment: ImageView

    private var convId: String = "c1"
    private var contactTitle: String = "Bạn bè"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        sessionManager = SessionManager(this)
        appDataStore = AppDataStore.getInstance(this)

        convId = intent.getStringExtra("EXTRA_CONV_ID") ?: "c1"
        contactTitle = intent.getStringExtra("EXTRA_CONV_TITLE") ?: "Bạn bè"

        initViews()
        loadMessages()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tvChatAvatar = findViewById(R.id.tvChatAvatar)
        tvChatContactName = findViewById(R.id.tvChatContactName)
        btnChatVoiceCall = findViewById(R.id.btnChatVoiceCall)
        btnChatVideoCall = findViewById(R.id.btnChatVideoCall)
        etMessageInput = findViewById(R.id.etMessageInput)
        btnSendMessage = findViewById(R.id.btnSendMessage)
        btnAttachment = findViewById(R.id.btnAttachment)

        tvChatContactName.text = contactTitle
        tvChatAvatar.text = contactTitle.ifBlank { "U" }.take(1).uppercase()

        btnBack.setOnClickListener { finish() }

        btnChatVoiceCall.setOnClickListener {
            val callItem = CallHistoryItem(
                id = "call_${System.currentTimeMillis()}",
                contactName = contactTitle,
                timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
                isVideo = false,
                isIncoming = false
            )
            appDataStore.addCallHistory(callItem)

            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("EXTRA_CONTACT_NAME", contactTitle)
                putExtra("EXTRA_IS_VIDEO", false)
            }
            startActivity(intent)
        }

        btnChatVideoCall.setOnClickListener {
            val callItem = CallHistoryItem(
                id = "call_${System.currentTimeMillis()}",
                contactName = contactTitle,
                timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
                isVideo = true,
                isIncoming = false
            )
            appDataStore.addCallHistory(callItem)

            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("EXTRA_CONTACT_NAME", contactTitle)
                putExtra("EXTRA_IS_VIDEO", true)
            }
            startActivity(intent)
        }

        rvMessages = findViewById(R.id.rvMessages)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        rvMessages.layoutManager = layoutManager
        messageAdapter = MessageAdapter(messages)
        rvMessages.adapter = messageAdapter

        btnSendMessage.setOnClickListener {
            sendMessage()
        }

        btnAttachment.setOnClickListener {
            showMediaUploadDialog()
        }

        val btnEmoji: ImageView = findViewById(R.id.btnEmoji)
        btnEmoji.setOnClickListener {
            etMessageInput.append(" 😊")
        }

        val btnVoiceRecord: ImageView = findViewById(R.id.btnVoiceRecord)
        btnVoiceRecord.setOnClickListener {
            Toast.makeText(this, "Nhấn giữ để gửi tin nhắn thoại", Toast.LENGTH_SHORT).show()
        }

        val btnChatMore: ImageView = findViewById(R.id.btnChatMore)
        btnChatMore.setOnClickListener {
            Toast.makeText(this, "Tùy chọn: Tìm kiếm tin nhắn, Đổi hình nền", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadMessages() {
        messages.clear()
        val saved = appDataStore.getMessages(convId)
        messages.addAll(saved)
        messageAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            rvMessages.scrollToPosition(messages.size - 1)
        }
    }

    private fun sendMessage() {
        val text = etMessageInput.text.toString().trim()
        if (text.isEmpty()) return

        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newMsg = Message(
            id = "msg_${System.currentTimeMillis()}",
            conversationId = convId,
            senderId = sessionManager.currentUser.id,
            senderName = sessionManager.currentUser.displayName,
            text = text,
            timestamp = currentTime,
            isMe = true
        )

        appDataStore.addMessage(convId, newMsg)
        messageAdapter.addMessage(newMsg)
        etMessageInput.setText("")
        rvMessages.smoothScrollToPosition(messages.size - 1)
    }

    private fun showMediaUploadDialog() {
        val options = arrayOf(
            "📷 Gửi ảnh chụp từ thư viện",
            "📁 Đính kèm tài liệu",
            "📸 Chụp ảnh nhanh từ camera"
        )

        AlertDialog.Builder(this)
            .setTitle("Đính kèm Media")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendSampleImageMessage()
                    1 -> sendSampleDocumentMessage()
                    2 -> Toast.makeText(this, "Mở camera chụp ảnh...", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun sendSampleImageMessage() {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val supabaseToken = sessionManager.supabaseToken ?: "token_user"

        ApiClient.signCloudinaryUpload(
            conversationId = convId,
            mimeType = "image/jpeg",
            sizeBytes = 256000,
            supabaseToken = supabaseToken
        ) { ticket ->
            val cloudUrl = if (ticket != null) {
                "https://res.cloudinary.com/${ticket.cloudName}/image/upload/${ticket.folder}/photo_${System.currentTimeMillis()}.jpg"
            } else {
                "https://images.unsplash.com/photo-1579202673506-ca3ce28943ef?w=600"
            }

            val imageMsg = Message(
                id = "media_${System.currentTimeMillis()}",
                conversationId = convId,
                senderId = sessionManager.currentUser.id,
                senderName = sessionManager.currentUser.displayName,
                text = "📷 [Hình ảnh]: $cloudUrl",
                timestamp = currentTime,
                isMe = true
            )
            appDataStore.addMessage(convId, imageMsg)
            messageAdapter.addMessage(imageMsg)
            rvMessages.smoothScrollToPosition(messages.size - 1)
            Toast.makeText(this, "Đã gửi hình ảnh thành công!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSampleDocumentMessage() {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val docMsg = Message(
            id = "doc_${System.currentTimeMillis()}",
            conversationId = convId,
            senderId = sessionManager.currentUser.id,
            senderName = sessionManager.currentUser.displayName,
            text = "📁 [Tài liệu]: TaiLieu_CongViec.pdf (1.2 MB)",
            timestamp = currentTime,
            isMe = true
        )
        appDataStore.addMessage(convId, docMsg)
        messageAdapter.addMessage(docMsg)
        rvMessages.smoothScrollToPosition(messages.size - 1)
        Toast.makeText(this, "Đã gửi tài liệu thành công!", Toast.LENGTH_SHORT).show()
    }
}
