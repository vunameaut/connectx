package com.vhn.connectx

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.vhn.connectx.adapter.EmojiAdapter
import com.vhn.connectx.adapter.MediaGalleryAdapter
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
    private lateinit var btnChatMore: ImageView
    private lateinit var etMessageInput: EditText
    private lateinit var btnSendMessage: FloatingActionButton
    private lateinit var btnPickImage: ImageView
    private lateinit var btnCamera: ImageView
    private lateinit var btnEmoji: ImageView
    private lateinit var btnVoiceRecord: ImageView

    // In-chat search elements
    private lateinit var layoutChatSearch: LinearLayout
    private lateinit var etSearchMessage: EditText
    private lateinit var tvSearchCount: TextView
    private lateinit var btnSearchClose: ImageView

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

        val myName = sessionManager.currentUser.displayName.trim()
        val myEmail = sessionManager.currentUser.email.trim().lowercase()

        // If contact has a custom stored nickname, load it
        val storedNick = appDataStore.getNickname(contactEmail)
        if (!storedNick.isNullOrBlank()) {
            contactTitle = storedNick
        } else if (contactTitle.equals(myName, ignoreCase = true) || contactTitle.isBlank() || contactTitle == "Trò chuyện") {
            val contactInDb = appDataStore.getContacts().find { it.email.equals(contactEmail, ignoreCase = true) }
            contactTitle = if (contactInDb != null && !contactInDb.displayName.equals(myName, ignoreCase = true) && contactInDb.displayName.isNotBlank()) {
                contactInDb.displayName
            } else if (contactEmail.isNotBlank()) {
                contactEmail.substringBefore("@")
            } else {
                "Bạn bè"
            }
        }

        // If convId is not direct normalized format and we have emails, normalize it
        if (!convId.startsWith("conv_") && contactEmail.isNotBlank()) {
            convId = appDataStore.getDirectConvId(myEmail, contactEmail)
        }

        // Persist the sanitized/resolved title right away so HomeActivity lists immediately reflect it
        appDataStore.updateConversationTitle(convId, contactTitle)
        if (contactEmail.isNotBlank()) {
            appDataStore.updateContactDisplayName(contactEmail, contactTitle)
        }

        initViews()
        loadLocalMessages()

        if (contactEmail.isNotBlank()) {
            com.vhn.connectx.net.FirebaseSyncManager.getUserProfile(contactEmail) { realUser ->
                if (realUser != null && realUser.displayName.isNotBlank() &&
                    !realUser.displayName.equals(myName, ignoreCase = true)) {
                    contactTitle = realUser.displayName
                    appDataStore.updateConversationTitle(convId, contactTitle)
                    appDataStore.addContact(realUser)
                    runOnUiThread {
                        tvChatContactName.text = contactTitle
                        tvChatAvatar.text = contactTitle.take(1).uppercase()
                    }
                }
            }
        }
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
                    val existingIndex = messages.indexOfFirst { it.id == remoteMsg.id }
                    if (existingIndex == -1) {
                        messages.add(remoteMsg)
                        appDataStore.addMessage(convId, remoteMsg)
                        hasNew = true
                    } else if (messages[existingIndex] != remoteMsg) {
                        messages[existingIndex] = remoteMsg
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
        btnChatMore = findViewById(R.id.btnChatMore)
        etMessageInput = findViewById(R.id.etMessageInput)
        btnSendMessage = findViewById(R.id.btnSendMessage)
        btnPickImage = findViewById(R.id.btnPickImage)
        btnCamera = findViewById(R.id.btnCamera)
        btnEmoji = findViewById(R.id.btnEmoji)
        btnVoiceRecord = findViewById(R.id.btnVoiceRecord)

        // Search Bar
        layoutChatSearch = findViewById(R.id.layoutChatSearch)
        etSearchMessage = findViewById(R.id.etSearchMessage)
        tvSearchCount = findViewById(R.id.tvSearchCount)
        btnSearchClose = findViewById(R.id.btnSearchClose)

        tvChatContactName.text = contactTitle
        tvChatAvatar.text = contactTitle.ifBlank { "U" }.take(1).uppercase()

        btnBack.setOnClickListener { finish() }

        btnChatVoiceCall.setOnClickListener {
            val callId = "call_${System.currentTimeMillis()}"
            val callItem = CallHistoryItem(
                id = callId,
                contactName = contactTitle,
                timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
                isVideo = false,
                isIncoming = false
            )
            appDataStore.addCallHistory(callItem)

            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("EXTRA_CALL_ID", callId)
                putExtra("EXTRA_CONTACT_NAME", contactTitle)
                putExtra("EXTRA_CONTACT_EMAIL", contactEmail)
                putExtra("EXTRA_IS_VIDEO", false)
                putExtra("EXTRA_IS_INCOMING", false)
            }
            startActivity(intent)
        }

        btnChatVideoCall.setOnClickListener {
            val callId = "call_${System.currentTimeMillis()}"
            val callItem = CallHistoryItem(
                id = callId,
                contactName = contactTitle,
                timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
                isVideo = true,
                isIncoming = false
            )
            appDataStore.addCallHistory(callItem)

            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("EXTRA_CALL_ID", callId)
                putExtra("EXTRA_CONTACT_NAME", contactTitle)
                putExtra("EXTRA_CONTACT_EMAIL", contactEmail)
                putExtra("EXTRA_IS_VIDEO", true)
                putExtra("EXTRA_IS_INCOMING", false)
            }
            startActivity(intent)
        }

        btnChatMore.setOnClickListener {
            showChatOptions()
        }

        rvMessages = findViewById(R.id.rvMessages)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        rvMessages.layoutManager = layoutManager
        messageAdapter = MessageAdapter(messages) { isVideo ->
            val callId = "call_${System.currentTimeMillis()}"
            val callItem = CallHistoryItem(
                id = callId,
                contactName = contactTitle,
                timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
                isVideo = isVideo,
                isIncoming = false
            )
            appDataStore.addCallHistory(callItem)

            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("EXTRA_CALL_ID", callId)
                putExtra("EXTRA_CONTACT_NAME", contactTitle)
                putExtra("EXTRA_CONTACT_EMAIL", contactEmail)
                putExtra("EXTRA_IS_VIDEO", isVideo)
                putExtra("EXTRA_IS_INCOMING", false)
            }
            startActivity(intent)
        }
        rvMessages.adapter = messageAdapter

        btnSendMessage.setOnClickListener {
            sendTextMessage()
        }

        btnPickImage.setOnClickListener {
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

        // Setup In-chat Search listeners
        btnSearchClose.setOnClickListener {
            layoutChatSearch.visibility = View.GONE
            etSearchMessage.setText("")
            messageAdapter.setMessages(messages)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etSearchMessage.windowToken, 0)
        }

        etSearchMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                if (query.isEmpty()) {
                    tvSearchCount.text = ""
                    messageAdapter.setMessages(messages)
                } else {
                    val filtered = messages.filter {
                        it.text.contains(query, ignoreCase = true)
                    }
                    tvSearchCount.text = "${filtered.size} kết quả"
                    messageAdapter.setMessages(filtered)
                    if (filtered.isNotEmpty()) {
                        rvMessages.scrollToPosition(filtered.size - 1)
                    }
                }
            }
        })
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

        // Upload to Cloud Firestore so the other phone gets it in REAL-TIME
        com.vhn.connectx.net.FirebaseSyncManager.sendMessage(convId, newMsg) {}

        // Also sync with backend server as fallback
        ApiClient.sendMessageToServer(this, convId, newMsg) { _ -> }
    }

    private fun handleImagePicked(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val imagesDir = File(cacheDir, "chat_images").apply { if (!exists()) mkdirs() }
            val localFile = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(localFile)

            val bitmap = BitmapFactory.decodeStream(inputStream)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            outputStream.flush()
            outputStream.close()
            inputStream.close()

            val scaledBitmap = if (bitmap.width > 800 || bitmap.height > 800) {
                val ratio = Math.min(800f / bitmap.width, 800f / bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
            } else {
                bitmap
            }
            val byteOut = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteOut)
            val base64Image = "data:image/jpeg;base64," + android.util.Base64.encodeToString(byteOut.toByteArray(), android.util.Base64.NO_WRAP)

            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val imageMsg = Message(
                id = "media_${System.currentTimeMillis()}",
                conversationId = convId,
                senderId = sessionManager.currentUser.id,
                senderName = sessionManager.currentUser.displayName,
                text = "📷 [Hình ảnh]",
                timestamp = currentTime,
                isMe = true,
                mediaUrl = base64Image,
                messageType = "image"
            )

            // Save and render locally
            appDataStore.addMessage(convId, imageMsg)
            messageAdapter.addMessage(imageMsg)
            rvMessages.smoothScrollToPosition(messages.size - 1)
            Toast.makeText(this, "Đã gửi hình ảnh!", Toast.LENGTH_SHORT).show()

            // Upload to Cloud Firestore for real-time delivery
            com.vhn.connectx.net.FirebaseSyncManager.sendMessage(convId, imageMsg) {}

            // Attempt Cloudinary upload in background if online
            val token = sessionManager.supabaseToken ?: "token"
            ApiClient.signCloudinaryUpload(this, convId, "image/jpeg", localFile.length(), token) { ticket ->
                if (ticket != null) {
                    val bytes = localFile.readBytes()
                    ApiClient.uploadToCloudinary(ticket, bytes, localFile.name) { cdnUrl ->
                        if (!cdnUrl.isNullOrBlank()) {
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
                    val existingIndex = messages.indexOfFirst { it.id == remoteMsg.id }
                    if (existingIndex == -1) {
                        messages.add(remoteMsg)
                        appDataStore.addMessage(convId, remoteMsg)
                        hasNew = true
                    } else if (messages[existingIndex] != remoteMsg) {
                        messages[existingIndex] = remoteMsg
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

    // --- ENHANCED GRID EMOJI PICKER (Messenger / Zalo style) ---
    private data class EmojiCategory(val title: String, val icon: String, val emojis: List<String>)

    private fun showEmojiSelector() {
        val bottomSheet = BottomSheetDialog(this)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_emoji_picker, null)
        bottomSheet.setContentView(dialogView)

        val categories = listOf(
            EmojiCategory("Mặt cười & Cảm xúc", "😀", listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊", "😇", "🥰",
                "😍", "🤩", "😘", "😗", "😚", "😙", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭",
                "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "🤥", "😌", "😔",
                "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🤮", "🤧", "🥵", "🥶", "🥴", "😵", "🤯",
                "🤠", "🥳", "😎", "🤓", "🧐", "🤡", "👻", "💩", "👽", "🤖", "🎃"
            )),
            EmojiCategory("Cử chỉ & Người", "👍", listOf(
                "👍", "👎", "👊", "✊", "🤛", "🤜", "🤞", "✌️", "🤟", "🤘", "👌", "🤏", "👈", "👉",
                "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "🤙", "💪", "✍️", "🙏", "🤝", "👏",
                "🙌", "👐", "🤲", "🦶", "👂", "👃", "🧠", "👀", "👁️", "👅", "👄", "👶", "👧", "🧒",
                "👦", "👩", "🧑", "👨", "👵", "🧓", "👴", "🙋", "🙆", "🙅", "💁", "🤷"
            )),
            EmojiCategory("Trái tim & Tình cảm", "❤️", listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓",
                "💗", "💖", "💘", "💝", "💟", "💌", "💋", "💯", "💢", "💥", "💫", "💦", "💨", "💣",
                "💬", "🗨️", "🗯️", "💭", "💤", "🔥", "✨", "⭐", "🌟", "❤️‍🔥", "❤️‍🩹"
            )),
            EmojiCategory("Động vật & Cây cỏ", "🐶", listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸",
                "🐵", "🐔", "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝",
                "🐛", "🦋", "🐌", "🐞", "🐜", "🐢", "🐍", "🐙", "🐬", "🐳", "🦈", "💐", "🌸", "🌹",
                "🌻", "🌺", "🌷", "🌱", "🌲", "🌳", "🌴", "🌵", "🍀", "🍁", "🍂", "🍃", "🍄", "🌈"
            )),
            EmojiCategory("Đồ ăn & Thức uống", "🍕", listOf(
                "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🍈", "🍒", "🍑", "🍍", "🥥",
                "🥝", "🍅", "🥑", "🥦", "🌽", "🥕", "🥔", "🥐", "🍞", "🥖", "🥨", "🧀", "🍳", "🥞",
                "🧇", "🥓", "🥩", "🍗", "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🍝", "🍜", "🍲",
                "🍛", "🍣", "🍱", "🥟", "🍤", "🍦", "🍧", "🍨", "🍩", "🍪", "🎂", "🍰", "🧁", "🍫",
                "🍬", "🍭", "🍮", "🍿", "☕", "🍵", "🧃", "🥤", "🍺", "🍻", "🥂", "🍷"
            )),
            EmojiCategory("Hoạt động & Đồ vật", "⚽", listOf(
                "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉", "🎱", "🏓", "🏸", "🥊", "🥋", "🎯", "🎮",
                "🎲", "🎳", "🎸", "🎹", "🥁", "🎷", "🎺", "🎻", "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️",
                "🎫", "🎬", "🎨", "🚗", "🚕", "🚙", "🚌", "🏎️", "🚓", "🚑", "🚒", "🚲", "🛴", "✈️",
                "🚀", "🛸", "🚢", "⌚", "📱", "💻", "📷", "🎥", "📞", "💡", "💰", "💵", "💳", "💎",
                "🎁", "🎈", "🎉", "🎊", "🔔", "🔑", "🔒"
            ))
        )

        val rvEmojiGrid: RecyclerView = dialogView.findViewById(R.id.rvEmojiGrid)
        val tvCategoryTitle: TextView = dialogView.findViewById(R.id.tvEmojiCategoryTitle)
        val layoutCategories: LinearLayout = dialogView.findViewById(R.id.layoutEmojiCategories)
        val btnBackspace: ImageView = dialogView.findViewById(R.id.btnEmojiBackspace)
        val btnDone: TextView = dialogView.findViewById(R.id.btnEmojiDone)

        rvEmojiGrid.layoutManager = GridLayoutManager(this, 7)
        val emojiAdapter = EmojiAdapter(categories[0].emojis) { emoji ->
            val start = etMessageInput.selectionStart.coerceAtLeast(0)
            val end = etMessageInput.selectionEnd.coerceAtLeast(0)
            etMessageInput.text.replace(Math.min(start, end), Math.max(start, end), emoji)
        }
        rvEmojiGrid.adapter = emojiAdapter

        // Populate category tab buttons
        val categoryViews = mutableListOf<TextView>()
        for ((index, category) in categories.withIndex()) {
            val tabView = TextView(this).apply {
                text = "${category.icon} ${category.title.split(" ").first()}"
                textSize = 13f
                setPadding(28, 16, 28, 16)
                setTextColor(if (index == 0) getColor(R.color.colorPrimary) else getColor(R.color.text_muted))
                setBackgroundResource(if (index == 0) R.drawable.bg_card_secondary else android.R.color.transparent)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    for ((i, v) in categoryViews.withIndex()) {
                        if (i == index) {
                            v.setTextColor(getColor(R.color.colorPrimary))
                            v.setBackgroundResource(R.drawable.bg_card_secondary)
                        } else {
                            v.setTextColor(getColor(R.color.text_muted))
                            v.setBackgroundResource(android.R.color.transparent)
                        }
                    }
                    tvCategoryTitle.text = category.title
                    emojiAdapter.updateList(category.emojis)
                    rvEmojiGrid.scrollToPosition(0)
                }
            }
            categoryViews.add(tabView)
            layoutCategories.addView(tabView)
        }

        btnBackspace.setOnClickListener {
            val start = etMessageInput.selectionStart
            val end = etMessageInput.selectionEnd
            val text = etMessageInput.text
            if (start > 0 && start == end) {
                val codePointBefore = Character.codePointBefore(text, start)
                val charCount = Character.charCount(codePointBefore)
                text.delete(start - charCount, start)
            } else if (start != end) {
                text.delete(Math.min(start, end), Math.max(start, end))
            }
        }

        btnDone.setOnClickListener {
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    // --- CHAT OPTIONS MENU (Zalo / Messenger Style) ---
    private fun showChatOptions() {
        val bottomSheet = BottomSheetDialog(this)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_chat_options, null)
        bottomSheet.setContentView(dialogView)

        val tvOptionAvatar: TextView = dialogView.findViewById(R.id.tvOptionAvatar)
        val tvOptionName: TextView = dialogView.findViewById(R.id.tvOptionName)
        val tvOptionEmail: TextView = dialogView.findViewById(R.id.tvOptionEmail)
        val tvNicknameSub: TextView = dialogView.findViewById(R.id.tvOptionNicknameSub)
        val tvMediaCount: TextView = dialogView.findViewById(R.id.tvOptionMediaCount)
        val tvNotificationStatus: TextView = dialogView.findViewById(R.id.tvOptionNotificationStatus)
        val switchNotification: SwitchMaterial = dialogView.findViewById(R.id.switchOptionNotification)

        val llVoiceCall: LinearLayout = dialogView.findViewById(R.id.llOptionVoiceCall)
        val llVideoCall: LinearLayout = dialogView.findViewById(R.id.llOptionVideoCall)
        val llSearch: LinearLayout = dialogView.findViewById(R.id.llOptionSearch)

        val itemSearchChat: LinearLayout = dialogView.findViewById(R.id.itemOptionSearchChat)
        val itemEditNickname: LinearLayout = dialogView.findViewById(R.id.itemOptionEditNickname)
        val itemMediaFiles: LinearLayout = dialogView.findViewById(R.id.itemOptionMediaFiles)
        val itemDeleteHistory: LinearLayout = dialogView.findViewById(R.id.itemOptionDeleteHistory)

        // Setup Header Data
        tvOptionName.text = contactTitle
        tvOptionAvatar.text = contactTitle.ifBlank { "U" }.take(1).uppercase()
        tvOptionEmail.text = contactEmail.ifBlank { "Tài khoản nội bộ" }

        val currentNickname = appDataStore.getNickname(contactEmail)
        tvNicknameSub.text = if (!currentNickname.isNullOrBlank()) "Biệt danh: $currentNickname" else "Đặt tên gợi nhớ cho người này"

        val mediaMessages = messages.filter { it.messageType == "image" || !it.mediaUrl.isNullOrBlank() }
        tvMediaCount.text = "${mediaMessages.size} hình ảnh trong đoạn chat"

        val isMuted = appDataStore.isChatMuted(convId)
        switchNotification.isChecked = !isMuted
        tvNotificationStatus.text = if (!isMuted) "Đang bật thông báo" else "Đã tắt thông báo"

        // Quick Calls
        llVoiceCall.setOnClickListener {
            bottomSheet.dismiss()
            btnChatVoiceCall.performClick()
        }

        llVideoCall.setOnClickListener {
            bottomSheet.dismiss()
            btnChatVideoCall.performClick()
        }

        val openSearchAction = {
            bottomSheet.dismiss()
            layoutChatSearch.visibility = View.VISIBLE
            etSearchMessage.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etSearchMessage, InputMethodManager.SHOW_IMPLICIT)
        }

        llSearch.setOnClickListener { openSearchAction() }
        itemSearchChat.setOnClickListener { openSearchAction() }

        // Change Nickname Action
        itemEditNickname.setOnClickListener {
            bottomSheet.dismiss()
            val input = EditText(this).apply {
                setText(contactTitle)
                setSelection(text.length)
                setPadding(48, 32, 48, 32)
                hint = "Nhập biệt danh mới"
            }
            AlertDialog.Builder(this)
                .setTitle("Đổi biệt danh")
                .setMessage("Đặt biệt danh hiển thị riêng cho người này:")
                .setView(input)
                .setPositiveButton("Lưu") { _, _ ->
                    val newNick = input.text.toString().trim()
                    appDataStore.setNickname(contactEmail, newNick)
                    val defaultFallback = if (contactEmail.isNotBlank()) contactEmail.substringBefore("@") else "Bạn bè"
                    contactTitle = if (newNick.isNotBlank()) newNick else defaultFallback
                    tvChatContactName.text = contactTitle
                    tvChatAvatar.text = contactTitle.take(1).uppercase()
                    appDataStore.updateConversationTitle(convId, contactTitle)
                    if (contactEmail.isNotBlank()) {
                        appDataStore.updateContactDisplayName(contactEmail, contactTitle)
                    }
                    Toast.makeText(this, "Đã cập nhật biệt danh: $contactTitle", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        // Media Gallery Viewer
        itemMediaFiles.setOnClickListener {
            bottomSheet.dismiss()
            showMediaGalleryDialog(mediaMessages)
        }

        // Notification Toggle
        switchNotification.setOnCheckedChangeListener { _, isChecked ->
            appDataStore.setChatMuted(convId, !isChecked)
            tvNotificationStatus.text = if (isChecked) "Đang bật thông báo" else "Đã tắt thông báo"
            Toast.makeText(this, if (isChecked) "Đã bật thông báo đoạn chat" else "Đã tắt thông báo đoạn chat", Toast.LENGTH_SHORT).show()
        }

        // Clear Chat History
        itemDeleteHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xóa lịch sử trò chuyện?")
                .setMessage("Tất cả tin nhắn trong đoạn chat với \"$contactTitle\" sẽ bị xóa khỏi máy của bạn. Hành động này không thể hoàn tác.")
                .setPositiveButton("Xóa tất cả") { _, _ ->
                    appDataStore.clearMessages(convId)
                    messages.clear()
                    messageAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "Đã xóa toàn bộ lịch sử trò chuyện", Toast.LENGTH_SHORT).show()
                    bottomSheet.dismiss()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        bottomSheet.show()
    }

    private fun showMediaGalleryDialog(mediaList: List<Message>) {
        val bottomSheet = BottomSheetDialog(this)
        val galleryView = LayoutInflater.from(this).inflate(R.layout.dialog_media_gallery, null)
        bottomSheet.setContentView(galleryView)

        val tvEmpty: TextView = galleryView.findViewById(R.id.tvEmptyGallery)
        val rvGallery: RecyclerView = galleryView.findViewById(R.id.rvMediaGallery)
        val btnClose: ImageView = galleryView.findViewById(R.id.btnGalleryClose)

        btnClose.setOnClickListener { bottomSheet.dismiss() }

        if (mediaList.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvGallery.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvGallery.visibility = View.VISIBLE
            rvGallery.layoutManager = GridLayoutManager(this, 3)
            rvGallery.adapter = MediaGalleryAdapter(mediaList)
        }

        bottomSheet.show()
    }
}
