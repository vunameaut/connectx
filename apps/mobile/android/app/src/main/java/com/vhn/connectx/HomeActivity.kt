package com.vhn.connectx

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.vhn.connectx.adapter.CallHistoryAdapter
import com.vhn.connectx.adapter.ContactAdapter
import com.vhn.connectx.adapter.ConversationAdapter
import com.vhn.connectx.adapter.FriendRequestAdapter
import com.vhn.connectx.adapter.StoryAdapter
import com.vhn.connectx.data.AppDataStore
import com.vhn.connectx.model.CallHistoryItem
import com.vhn.connectx.model.Conversation
import com.vhn.connectx.model.FriendRequest
import com.vhn.connectx.model.SessionManager
import com.vhn.connectx.model.User
import com.vhn.connectx.net.ApiClient
import com.vhn.connectx.update.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var appDataStore: AppDataStore

    // Views
    private lateinit var tvHeaderTitle: TextView
    private lateinit var btnSearch: ImageView
    private lateinit var btnAddAction: ImageView

    private lateinit var viewChats: View
    private lateinit var viewContacts: View
    private lateinit var viewCalls: View
    private lateinit var viewProfile: View
    private lateinit var bottomNav: BottomNavigationView

    // Tab 1: Chats
    private lateinit var layoutStoriesRow: LinearLayout
    private lateinit var rvOnlineStories: RecyclerView
    private lateinit var storyAdapter: StoryAdapter
    private val onlineUsers = mutableListOf<User>()

    private lateinit var layoutEmptyChats: View
    private lateinit var tvRecentChatsHeader: TextView
    private lateinit var rvConversations: RecyclerView
    private lateinit var conversationAdapter: ConversationAdapter
    private val conversations = mutableListOf<Conversation>()
    private lateinit var btnEmptyChatAddFriend: MaterialButton
    private lateinit var fabNewChat: FloatingActionButton

    // Tab 2: Contacts & Friend Requests
    private lateinit var layoutPendingRequests: LinearLayout
    private lateinit var tvPendingCountBadge: TextView
    private lateinit var rvFriendRequests: RecyclerView
    private lateinit var friendRequestAdapter: FriendRequestAdapter
    private val pendingRequests = mutableListOf<FriendRequest>()

    private lateinit var layoutEmptyContacts: View
    private lateinit var tvContactsHeader: TextView
    private lateinit var rvContacts: RecyclerView
    private lateinit var contactAdapter: ContactAdapter
    private val contacts = mutableListOf<User>()
    private lateinit var btnQuickAddFriend: MaterialButton

    // Tab 3: Calls
    private lateinit var layoutEmptyCalls: View
    private lateinit var tvCallHistoryHeader: TextView
    private lateinit var rvCallHistory: RecyclerView
    private lateinit var callHistoryAdapter: CallHistoryAdapter
    private val callHistory = mutableListOf<CallHistoryItem>()
    private lateinit var btnQuickVoiceCall: MaterialButton
    private lateinit var btnQuickVideoCall: MaterialButton

    // Tab 4: Profile
    private lateinit var tvProfileDisplayName: TextView
    private lateinit var tvProfileEmail: TextView
    private lateinit var tvProfileLargeAvatar: TextView
    private lateinit var btnToggleTheme: LinearLayout
    private lateinit var tvCurrentThemeLabel: TextView
    private lateinit var btnAboutDevice: LinearLayout
    private lateinit var btnServerConfig: LinearLayout
    private lateinit var tvCurrentServerHost: TextView
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnCheckUpdate: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)
        appDataStore = AppDataStore.getInstance(this)

        if (!sessionManager.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)

        initViews()
        setupBottomNav()
        refreshData()

        // Check for updates
        UpdateManager.checkForUpdate(this, isManual = false)
    }

    override fun onResume() {
        super.onResume()
        refreshData()
        syncFriendRequestsFromServer()
    }

    private fun initViews() {
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        btnSearch = findViewById(R.id.btnSearch)
        btnAddAction = findViewById(R.id.btnAddAction)

        viewChats = findViewById(R.id.viewChats)
        viewContacts = findViewById(R.id.viewContacts)
        viewCalls = findViewById(R.id.viewCalls)
        viewProfile = findViewById(R.id.viewProfile)
        bottomNav = findViewById(R.id.bottomNavigation)

        // Tab 1: Chats
        layoutStoriesRow = findViewById(R.id.layoutStoriesRow)
        rvOnlineStories = findViewById(R.id.rvOnlineStories)
        rvOnlineStories.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        storyAdapter = StoryAdapter(onlineUsers) { targetUser ->
            openChatWithUser(targetUser)
        }
        rvOnlineStories.adapter = storyAdapter

        layoutEmptyChats = findViewById(R.id.layoutEmptyChats)
        tvRecentChatsHeader = findViewById(R.id.tvRecentChatsHeader)
        rvConversations = findViewById(R.id.rvConversations)
        rvConversations.layoutManager = LinearLayoutManager(this)
        conversationAdapter = ConversationAdapter(conversations) { conv ->
            openChat(conv.id, conv.title, "")
        }
        rvConversations.adapter = conversationAdapter

        btnEmptyChatAddFriend = findViewById(R.id.btnEmptyChatAddFriend)
        btnEmptyChatAddFriend.setOnClickListener { showSendFriendRequestDialog() }

        fabNewChat = findViewById(R.id.fabNewChat)
        fabNewChat.setOnClickListener { showSelectFriendToChatDialog() }

        // Tab 2: Contacts & Friend Requests
        layoutPendingRequests = findViewById(R.id.layoutPendingRequests)
        tvPendingCountBadge = findViewById(R.id.tvPendingCountBadge)
        rvFriendRequests = findViewById(R.id.rvFriendRequests)
        rvFriendRequests.layoutManager = LinearLayoutManager(this)
        friendRequestAdapter = FriendRequestAdapter(
            pendingRequests,
            onAccept = { req -> handleAcceptFriendRequest(req) },
            onReject = { req -> handleRejectFriendRequest(req) }
        )
        rvFriendRequests.adapter = friendRequestAdapter

        layoutEmptyContacts = findViewById(R.id.layoutEmptyContacts)
        tvContactsHeader = findViewById(R.id.tvContactsHeader)
        rvContacts = findViewById(R.id.rvContacts)
        rvContacts.layoutManager = LinearLayoutManager(this)
        contactAdapter = ContactAdapter(
            contacts,
            onChatClick = { contact -> openChatWithUser(contact) },
            onCallClick = { contact -> startCall(contact.displayName, isVideo = false) }
        )
        rvContacts.adapter = contactAdapter

        btnQuickAddFriend = findViewById(R.id.btnQuickAddFriend)
        btnQuickAddFriend.setOnClickListener { showSendFriendRequestDialog() }
        btnAddAction.setOnClickListener { showSendFriendRequestDialog() }

        // Tab 3: Calls
        layoutEmptyCalls = findViewById(R.id.layoutEmptyCalls)
        tvCallHistoryHeader = findViewById(R.id.tvCallHistoryHeader)
        rvCallHistory = findViewById(R.id.rvCallHistory)
        rvCallHistory.layoutManager = LinearLayoutManager(this)
        callHistoryAdapter = CallHistoryAdapter(callHistory) { item ->
            startCall(item.contactName, item.isVideo)
        }
        rvCallHistory.adapter = callHistoryAdapter

        btnQuickVoiceCall = findViewById(R.id.btnQuickVoiceCall)
        btnQuickVideoCall = findViewById(R.id.btnQuickVideoCall)

        // Select contact before calling to avoid blind calling
        btnQuickVoiceCall.setOnClickListener {
            showSelectContactToCallDialog(isVideo = false)
        }
        btnQuickVideoCall.setOnClickListener {
            showSelectContactToCallDialog(isVideo = true)
        }

        // Tab 4: Profile
        val user = sessionManager.currentUser
        tvProfileDisplayName = findViewById(R.id.tvProfileDisplayName)
        tvProfileEmail = findViewById(R.id.tvProfileEmail)
        tvProfileLargeAvatar = findViewById(R.id.tvProfileLargeAvatar)
        btnToggleTheme = findViewById(R.id.btnToggleTheme)
        tvCurrentThemeLabel = findViewById(R.id.tvCurrentThemeLabel)
        btnAboutDevice = findViewById(R.id.btnAboutDevice)
        btnServerConfig = findViewById(R.id.btnServerConfig)
        tvCurrentServerHost = findViewById(R.id.tvCurrentServerHost)
        btnLogout = findViewById(R.id.btnLogout)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)

        tvProfileDisplayName.text = user.displayName
        tvProfileEmail.text = user.email
        val initial = user.displayName.ifBlank { user.email.substringBefore("@") }.ifBlank { "U" }.take(1).uppercase()
        tvProfileLargeAvatar.text = initial

        updateThemeLabel()
        updateServerHostLabel()

        btnToggleTheme.setOnClickListener {
            showThemeSelectionDialog()
        }

        btnAboutDevice.setOnClickListener {
            showAboutDeviceDialog()
        }

        btnServerConfig.setOnClickListener {
            showServerConfigDialog()
        }

        btnCheckUpdate.setOnClickListener {
            UpdateManager.checkForUpdate(this, isManual = true)
        }

        btnLogout.setOnClickListener {
            sessionManager.logout()
            Toast.makeText(this, "Đã đăng xuất tài khoản", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        btnSearch.setOnClickListener {
            Toast.makeText(this, "Tìm kiếm bạn bè & cuộc trò chuyện", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> switchTab(0)
                R.id.nav_contacts -> switchTab(1)
                R.id.nav_calls -> switchTab(2)
                R.id.nav_profile -> switchTab(3)
                else -> false
            }
        }
    }

    private fun switchTab(tabIndex: Int): Boolean {
        viewChats.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        viewContacts.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        viewCalls.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        viewProfile.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE

        tvHeaderTitle.text = when (tabIndex) {
            0 -> getString(R.string.tab_chats)
            1 -> getString(R.string.tab_contacts)
            2 -> getString(R.string.tab_calls)
            3 -> getString(R.string.tab_profile)
            else -> "ConnectX"
        }
        return true
    }

    private fun refreshData() {
        val myEmail = sessionManager.currentUser.email

        // 1. Pending Friend Requests
        pendingRequests.clear()
        pendingRequests.addAll(appDataStore.getPendingRequestsFor(myEmail))
        friendRequestAdapter.notifyDataSetChanged()

        if (pendingRequests.isNotEmpty()) {
            layoutPendingRequests.visibility = View.VISIBLE
            tvPendingCountBadge.text = pendingRequests.size.toString()
        } else {
            layoutPendingRequests.visibility = View.GONE
        }

        // 2. Contacts
        contacts.clear()
        contacts.addAll(appDataStore.getContacts())
        contactAdapter.notifyDataSetChanged()

        if (contacts.isEmpty()) {
            layoutEmptyContacts.visibility = View.VISIBLE
            tvContactsHeader.visibility = View.GONE
            rvContacts.visibility = View.GONE
        } else {
            layoutEmptyContacts.visibility = View.GONE
            tvContactsHeader.visibility = View.VISIBLE
            rvContacts.visibility = View.VISIBLE
        }

        // Online stories
        onlineUsers.clear()
        onlineUsers.addAll(contacts)
        storyAdapter.notifyDataSetChanged()
        layoutStoriesRow.visibility = if (onlineUsers.isNotEmpty()) View.VISIBLE else View.GONE

        // 3. Conversations
        conversations.clear()
        conversations.addAll(appDataStore.getConversations())
        conversationAdapter.notifyDataSetChanged()

        if (conversations.isEmpty()) {
            layoutEmptyChats.visibility = View.VISIBLE
            tvRecentChatsHeader.visibility = View.GONE
            rvConversations.visibility = View.GONE
        } else {
            layoutEmptyChats.visibility = View.GONE
            tvRecentChatsHeader.visibility = View.VISIBLE
            rvConversations.visibility = View.VISIBLE
        }

        // 4. Call history
        callHistory.clear()
        callHistory.addAll(appDataStore.getCallHistory())
        callHistoryAdapter.notifyDataSetChanged()

        if (callHistory.isEmpty()) {
            layoutEmptyCalls.visibility = View.VISIBLE
            tvCallHistoryHeader.visibility = View.GONE
            rvCallHistory.visibility = View.GONE
        } else {
            layoutEmptyCalls.visibility = View.GONE
            tvCallHistoryHeader.visibility = View.VISIBLE
            rvCallHistory.visibility = View.VISIBLE
        }
    }

    private fun syncFriendRequestsFromServer() {
        val myEmail = sessionManager.currentUser.email
        if (myEmail.isBlank()) return

        ApiClient.syncFriendRequests(this, myEmail) { serverRequests ->
            if (serverRequests.isNotEmpty()) {
                var changed = false
                for (sReq in serverRequests) {
                    if (sReq.receiverEmail.equals(myEmail, ignoreCase = true) && sReq.status == "PENDING") {
                        val added = appDataStore.sendFriendRequest(sReq)
                        if (added) changed = true
                    }
                }
                if (changed) {
                    refreshData()
                }
            }
        }
    }

    private fun showSendFriendRequestDialog() {
        val input = EditText(this).apply {
            hint = "Nhập email bạn bè (vd: ban@gmail.com)"
            setPadding(44, 32, 44, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Gửi lời mời kết bạn")
            .setMessage("Nhập email của người bạn muốn kết nối. Đối phương sẽ nhận được lời mời và đồng ý trước khi cả hai bắt đầu trò chuyện:")
            .setView(input)
            .setPositiveButton("Gửi lời mời") { _, _ ->
                val targetEmail = input.text.toString().trim()
                val myUser = sessionManager.currentUser

                if (targetEmail.isEmpty()) {
                    Toast.makeText(this, "Vui lòng nhập địa chỉ email", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (targetEmail.equals(myUser.email, ignoreCase = true)) {
                    Toast.makeText(this, "Bạn không thể gửi lời mời kết bạn cho chính mình", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (contacts.any { it.email.equals(targetEmail, ignoreCase = true) }) {
                    Toast.makeText(this, "Người này đã có trong danh bạ của bạn", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newReq = FriendRequest(
                    id = "fr_${System.currentTimeMillis()}",
                    senderId = myUser.id,
                    senderName = myUser.displayName,
                    senderEmail = myUser.email,
                    receiverEmail = targetEmail,
                    timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
                    status = "PENDING"
                )

                // Save locally
                appDataStore.sendFriendRequest(newReq)

                // Send to backend server
                ApiClient.sendFriendRequest(this, newReq) { success ->
                    // Request sent to server
                }

                Toast.makeText(this, "Đã gửi lời mời kết bạn tới $targetEmail! Vui lòng chờ đối phương chấp nhận.", Toast.LENGTH_LONG).show()
                refreshData()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun handleAcceptFriendRequest(req: FriendRequest) {
        val myUser = sessionManager.currentUser
        val newFriend = appDataStore.acceptFriendRequest(req.id, myUser)
        if (newFriend != null) {
            ApiClient.respondFriendRequest(this, req.id, "ACCEPTED") {}
            Toast.makeText(this, "Đã trở thành bạn bè với ${newFriend.displayName}!", Toast.LENGTH_SHORT).show()
            refreshData()
            // Open direct chat
            openChatWithUser(newFriend)
        }
    }

    private fun handleRejectFriendRequest(req: FriendRequest) {
        appDataStore.rejectFriendRequest(req.id)
        ApiClient.respondFriendRequest(this, req.id, "REJECTED") {}
        Toast.makeText(this, "Đã từ chối lời mời kết bạn", Toast.LENGTH_SHORT).show()
        refreshData()
    }

    private fun showSelectFriendToChatDialog() {
        if (contacts.isEmpty()) {
            Toast.makeText(this, "Bạn chưa có bạn bè trong danh bạ. Hãy gửi lời mời kết bạn trước!", Toast.LENGTH_LONG).show()
            showSendFriendRequestDialog()
            return
        }

        val names = contacts.map { "${it.displayName} (${it.email})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bắt đầu cuộc trò chuyện mới")
            .setItems(names) { _, which ->
                val selectedUser = contacts[which]
                openChatWithUser(selectedUser)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showSelectContactToCallDialog(isVideo: Boolean) {
        if (contacts.isEmpty()) {
            val callType = if (isVideo) "gọi video" else "gọi thoại"
            Toast.makeText(this, "Danh bạ đang trống. Hãy kết bạn trước khi thực hiện $callType!", Toast.LENGTH_LONG).show()
            showSendFriendRequestDialog()
            return
        }

        val title = if (isVideo) "📹 Chọn người muốn gọi Video" else "📞 Chọn người muốn gọi Thoại"
        val names = contacts.map { "${it.displayName} • ${it.email}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, which ->
                val selectedContact = contacts[which]
                startCall(selectedContact.displayName, isVideo)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun openChatWithUser(user: User) {
        val convId = appDataStore.getDirectConvId(sessionManager.currentUser.email, user.email)
        openChat(convId, user.displayName, user.email)
    }

    private fun openChat(conversationId: String, title: String, contactEmail: String) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("EXTRA_CONV_ID", conversationId)
            putExtra("EXTRA_CONV_TITLE", title)
            putExtra("EXTRA_CONTACT_EMAIL", contactEmail)
        }
        startActivity(intent)
    }

    private fun startCall(contactName: String, isVideo: Boolean) {
        val callItem = CallHistoryItem(
            id = "call_${System.currentTimeMillis()}",
            contactName = contactName,
            timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
            isVideo = isVideo,
            isIncoming = false
        )
        appDataStore.addCallHistory(callItem)

        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("EXTRA_CONTACT_NAME", contactName)
            putExtra("EXTRA_IS_VIDEO", isVideo)
        }
        startActivity(intent)
    }

    private fun updateThemeLabel() {
        tvCurrentThemeLabel.text = when (appDataStore.themeMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> "Giao diện Tối"
            AppCompatDelegate.MODE_NIGHT_NO -> "Giao diện Sáng"
            else -> "Theo hệ thống"
        }
    }

    private fun updateServerHostLabel() {
        val host = appDataStore.customServerHost
        tvCurrentServerHost.text = if (host.isNullOrBlank()) "10.0.2.2:4000 (Mặc định)" else host.removePrefix("http://").removePrefix("https://")
    }

    private fun showServerConfigDialog() {
        val input = EditText(this).apply {
            setText(appDataStore.customServerHost ?: "http://10.0.2.2:4000")
            hint = "http://192.168.1.xxx:4000"
            setPadding(44, 32, 44, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Cấu hình Máy chủ Kết nối")
            .setMessage("Nhập địa chỉ IP Backend API để đồng bộ dữ liệu và gọi WebRTC giữa các thiết bị (Dùng 10.0.2.2 cho máy ảo, hoặc IP LAN máy tính khi chạy trên điện thoại thật):")
            .setView(input)
            .setPositiveButton("Lưu cấu hình") { _, _ ->
                val newHost = input.text.toString().trim()
                if (newHost.isNotEmpty()) {
                    appDataStore.customServerHost = newHost
                    ApiClient.apiBaseUrl = newHost
                    updateServerHostLabel()
                    Toast.makeText(this, "Đã cập nhật máy chủ: $newHost", Toast.LENGTH_SHORT).show()
                    syncFriendRequestsFromServer()
                }
            }
            .setNeutralButton("Khôi phục mặc định") { _, _ ->
                appDataStore.customServerHost = null
                ApiClient.apiBaseUrl = "http://10.0.2.2:4000"
                updateServerHostLabel()
                Toast.makeText(this, "Đã khôi phục địa chỉ mặc định", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showThemeSelectionDialog() {
        val options = arrayOf("Giao diện Sáng (Light)", "Giao diện Tối (Dark)", "Theo cài đặt hệ thống")
        val currentChecked = when (appDataStore.themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> 0
            AppCompatDelegate.MODE_NIGHT_YES -> 1
            else -> 2
        }

        AlertDialog.Builder(this)
            .setTitle("Tùy chọn giao diện")
            .setSingleChoiceItems(options, currentChecked) { dialog, which ->
                val selectedMode = when (which) {
                    0 -> AppCompatDelegate.MODE_NIGHT_NO
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                appDataStore.themeMode = selectedMode
                updateThemeLabel()
                dialog.dismiss()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showAboutDeviceDialog() {
        val appVersion = BuildConfig.VERSION_NAME
        val deviceModel = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        val infoMessage = """
            📱 THIẾT BỊ & HỆ THỐNG
            • Thiết bị: $deviceModel
            • Phiên bản HĐH: $androidVersion
            • Kiến trúc CPU: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}

            🚀 ỨNG DỤNG CONNECTX
            • Phiên bản: v$appVersion (Bản phát hành chính thức)
            • Trình bảo vệ: FLAG_SECURE (Chống quay màn hình trái phép)
            • Truyền thông: WebRTC Audio/Video Engine + Screen Sharing
            • Mã hóa: Bảo mật kết nối E2E

            © 2026 ConnectX Corporation. All rights reserved.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Giới thiệu ứng dụng & Thiết bị")
            .setMessage(infoMessage)
            .setPositiveButton("Đóng", null)
            .show()
    }
}
