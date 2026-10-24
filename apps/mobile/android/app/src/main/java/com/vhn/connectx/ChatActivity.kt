package com.vhn.connectx

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.File
import java.io.FileOutputStream
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
    private lateinit var btnPickImage: ImageView
    private lateinit var btnCamera: ImageView
    private lateinit var btnEmoji: ImageView
    private lateinit var btnVoiceRecord: ImageView

    private var convId: String = "c1"
    private var contactTitle: String = "Bạn bè"
    private var contactEmail: String = ""

    private val syncHandler = Handler(Looper.getMainLooper())
    private var isSyncing = false

    private val syncRunnable = object : Runnable {
        override fun run() {
            pollMessagesFromServer()
            syncHandler.postDelayed(this, 2500) // Poll every 2.5 seconds
        }
    }

    // Photo picker launcher from Android system gallery
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handleImagePicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        sessionManager = SessionManager(this)
        appDataStore = AppDataStore.getInstance(this)

        convId = intent.getStringExtra("EXTRA_CONV_ID") ?: "c1"
        contactTitle = intent.getStringExtra("EXTRA_CONV_TITLE") ?: "Bạn bè"
        contactEmail = intent.getStringExtra("EXTRA_CONTACT_EMAIL") ?: ""

        // If convId is not direct normalized format and we have emails, normalize it
        if (!convId.startsWith("conv_") && contactEmail.isNotBlank()) {
            convId = appDataStore.getDirectConvId(sessionManager.currentUser.email, contactEmail)
        }

        initViews()
        loadLocalMessages()
    }

    private var messageListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onResume() {
        super.onResume()
        pollMessagesFromServer()
        syncHandler.postDelayed(syncRunnable, 2500)

        // Real-time Cloud Firestore Message Listener
        messageListener?.remove()
        messageListener = com.vhn.connectx.net.FirebaseSyncManager.listenMessages(convId, sessionManager.currentUser.id) { remoteMessages ->
            if (remoteMessages.isNotEmpty()) {
                var hasNew = false
                for (remoteMsg in remoteMessages) {
                    if (messages.none { it.id == remoteMsg.id }) {
                        messages.add(remoteMsg)
                        appDataStore.addMessage(convId, remoteMsg)
                        hasNew = true
                    }
                }
                if (hasNew) {
                    messageAdapter.notifyDataSetChanged()
                    rvMessages.smoothScrollToPosition(messages.size - 1)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        syncHandler.removeCallbacks(syncRunnable)
        messageListener?.remove()
        messageListener = null
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tvChatAvatar = findViewById(R.id.tvChatAvatar)
        tvChatContactName = findViewById(R.id.tvChatContactName)
        btnChatVoiceCall = findViewById(R.id.btnChatVoiceCall)
        btnChatVideoCall = findViewById(R.id.btnChatVideoCall)
        etMessageInput = findViewById(R.id.etMessageInput)
        btnSendMessage = findViewById(R.id.btnSendMessage)
        btnPickImage = findViewById(R.id.btnPickImage)
        btnCamera = findViewById(R.id.btnCamera)
        btnEmoji = findViewById(R.id.btnEmoji)
        btnVoiceRecord = findViewById(R.id.btnVoiceRecord)

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
            sendTextMessage()
        }

        btnPickImage.setOnClickListener {
            // Open Android system photo picker
            pickImageLauncher.launch("image/*")
        }

        btnCamera.setOnClickListener {
            Toast.makeText(this, "Mở máy ảnh chụp ảnh nhanh...", Toast.LENGTH_SHORT).show()
            pickImageLauncher.launch("image/*")
        }

        btnEmoji.setOnClickListener {
            showEmojiSelector()
        }

        btnVoiceRecord.setOnClickListener {
            Toast.makeText(this, "Đang ghi âm tin nhắn thoại... Thả ra để gửi", Toast.LENGTH_SHORT).show()
        }

        val btnChatMore: ImageView = findViewById(R.id.btnChatMore)
        btnChatMore.setOnClickListener {
            Toast.makeText(this, "Tùy chọn: Tìm kiếm tin nhắn, Đổi hình nền", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLocalMessages() {
        messages.clear()
        val saved = appDataStore.getMessages(convId)
        messages.addAll(saved)
        messageAdapter.notifyDataSetChanged()
        if (messages.isNotEmpty()) {
            rvMessages.scrollToPosition(messages.size - 1)
        }
    }

    private fun sendTextMessage() {
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
            isMe = true,
            mediaUrl = null,
            messageType = "text"
        )

        // Save locally
        appDataStore.addMessage(convId, newMsg)
        messageAdapter.addMessage(newMsg)
        etMessageInput.setText("")
        rvMessages.smoothScrollToPosition(messages.size - 1)

        // Upload to Cloud Firestore so the other phone gets it in REAL-TIME (no server needed)
        com.vhn.connectx.net.FirebaseSyncManager.sendMessage(convId, newMsg) {}

        // Also sync with backend server as fallback
        ApiClient.sendMessageToServer(this, convId, newMsg) { success -> }
    }

    private fun handleImagePicked(uri: Uri) {
        try {
            // Copy selected image to local cache for instant viewing
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val imagesDir = File(cacheDir, "chat_images").apply { if (!exists()) mkdirs() }
            val localFile = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(localFile)

            val bitmap = BitmapFactory.decodeStream(inputStream)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            outputStream.flush()
            outputStream.close()
            inputStream.close()

            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val imageMsg = Message(
                id = "media_${System.currentTimeMillis()}",
                conversationId = convId,
                senderId = sessionManager.currentUser.id,
                senderName = sessionManager.currentUser.displayName,
                text = "📷 [Hình ảnh]",
                timestamp = currentTime,
                isMe = true,
                mediaUrl = localFile.absolutePath,
                messageType = "image"
            )

            // Save and render locally
            appDataStore.addMessage(convId, imageMsg)
            messageAdapter.addMessage(imageMsg)
            rvMessages.smoothScrollToPosition(messages.size - 1)
            Toast.makeText(this, "Đã gửi hình ảnh!", Toast.LENGTH_SHORT).show()

            // Upload to Cloud Firestore for real-time delivery
            com.vhn.connectx.net.FirebaseSyncManager.sendMessage(convId, imageMsg) {}

            // Synchronize with server fallback
            ApiClient.sendMessageToServer(this, convId, imageMsg) { success -> }

            // Attempt Cloudinary upload in background if online
            val token = sessionManager.supabaseToken ?: "token"
            ApiClient.signCloudinaryUpload(convId, "image/jpeg", localFile.length(), token) { ticket ->
                if (ticket != null) {
                    val bytes = localFile.readBytes()
                    ApiClient.uploadToCloudinary(ticket, bytes, localFile.name) { cdnUrl ->
                        if (!cdnUrl.isNullOrBlank()) {
                            // Update message with CDN url
                            val updatedMsg = imageMsg.copy(mediaUrl = cdnUrl)
                            ApiClient.sendMessageToServer(this@ChatActivity, convId, updatedMsg) {}
                        }
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Không thể gửi ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pollMessagesFromServer() {
        if (isSyncing) return
        isSyncing = true

        ApiClient.syncMessages(this, convId, sessionManager.currentUser.id) { remoteMessages ->
            isSyncing = false
            if (remoteMessages.isNotEmpty()) {
                var hasNew = false
                for (remoteMsg in remoteMessages) {
                    if (messages.none { it.id == remoteMsg.id }) {
                        messages.add(remoteMsg)
                        appDataStore.addMessage(convId, remoteMsg)
                        hasNew = true
                    }
                }
                if (hasNew) {
                    messageAdapter.notifyDataSetChanged()
                    rvMessages.smoothScrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun showEmojiSelector() {
        val emojis = arrayOf("😊", "😂", "❤️", "👍", "🎉", "🔥", "✨", "🙏", "😍", "🥳", "😎", "🤝")
        AlertDialog.Builder(this)
            .setTitle("Chọn biểu tượng cảm xúc")
            .setItems(emojis) { _, which ->
                etMessageInput.append(" " + emojis[which])
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}
