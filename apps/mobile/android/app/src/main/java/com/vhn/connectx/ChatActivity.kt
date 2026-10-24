package com.vhn.connectx

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
import com.vhn.connectx.model.Message
import com.vhn.connectx.model.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
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
    private var contactTitle: String = "Bạn bè ConnectX"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        sessionManager = SessionManager(this)

        convId = intent.getStringExtra("EXTRA_CONV_ID") ?: "c1"
        contactTitle = intent.getStringExtra("EXTRA_CONV_TITLE") ?: "Bạn bè ConnectX"

        initViews()
        loadInitialMessages()
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
        tvChatAvatar.text = contactTitle.take(1).uppercase()

        btnBack.setOnClickListener { finish() }

        btnChatVoiceCall.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("EXTRA_CONTACT_NAME", contactTitle)
                putExtra("EXTRA_IS_VIDEO", false)
            }
            startActivity(intent)
        }

        btnChatVideoCall.setOnClickListener {
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
            Toast.makeText(this, "Chia sẻ ảnh hoặc tài liệu đính kèm", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadInitialMessages() {
        messages.clear()
        messages.add(
            Message(
                id = "m1",
                conversationId = convId,
                senderId = "other",
                senderName = contactTitle,
                text = "Chào bạn! Dự án ConnectX đã hoàn thành các module chat và cuộc gọi rồi nhé.",
                timestamp = "14:30",
                isMe = false
            )
        )
        messages.add(
            Message(
                id = "m2",
                conversationId = convId,
                senderId = sessionManager.currentUser.id,
                senderName = sessionManager.currentUser.displayName,
                text = "Tuyệt vời quá! Bạn bấm nút gọi video rồi chia sẻ màn hình thử nhé.",
                timestamp = "14:31",
                isMe = true
            )
        )
        messages.add(
            Message(
                id = "m3",
                conversationId = convId,
                senderId = "other",
                senderName = contactTitle,
                text = "Ok, mình sẵn sàng rồi đây!",
                timestamp = "14:32",
                isMe = false
            )
        )
        messageAdapter.notifyDataSetChanged()
        rvMessages.scrollToPosition(messages.size - 1)
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

        messageAdapter.addMessage(newMsg)
        etMessageInput.setText("")
        rvMessages.smoothScrollToPosition(messages.size - 1)
    }
}
