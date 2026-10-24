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
import com.vhn.connectx.adapter.CallHistoryAdapter
import com.vhn.connectx.adapter.ContactAdapter
import com.vhn.connectx.adapter.ConversationAdapter
import com.vhn.connectx.adapter.StoryAdapter
import com.vhn.connectx.data.AppDataStore
import com.vhn.connectx.model.CallHistoryItem
import com.vhn.connectx.model.Conversation
import com.vhn.connectx.model.SessionManager
import com.vhn.connectx.model.User
import com.vhn.connectx.update.UpdateManager

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

    // Tab 2: Contacts
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
            openChat("c_${targetUser.id}", targetUser.displayName)
        }
        rvOnlineStories.adapter = storyAdapter

        layoutEmptyChats = findViewById(R.id.layoutEmptyChats)
        tvRecentChatsHeader = findViewById(R.id.tvRecentChatsHeader)
        rvConversations = findViewById(R.id.rvConversations)
        rvConversations.layoutManager = LinearLayoutManager(this)
        conversationAdapter = ConversationAdapter(conversations) { conv ->
            openChat(conv.id, conv.title)
        }
        rvConversations.adapter = conversationAdapter

        btnEmptyChatAddFriend = findViewById(R.id.btnEmptyChatAddFriend)
        btnEmptyChatAddFriend.setOnClickListener { showAddFriendDialog() }

        // Tab 2: Contacts
        layoutEmptyContacts = findViewById(R.id.layoutEmptyContacts)
        tvContactsHeader = findViewById(R.id.tvContactsHeader)
        rvContacts = findViewById(R.id.rvContacts)
        rvContacts.layoutManager = LinearLayoutManager(this)
        contactAdapter = ContactAdapter(
            contacts,
            onChatClick = { contact -> openChat("c_${contact.id}", contact.displayName) },
            onCallClick = { contact -> startCall(contact.displayName, isVideo = false) }
        )
        rvContacts.adapter = contactAdapter

        btnQuickAddFriend = findViewById(R.id.btnQuickAddFriend)
        btnQuickAddFriend.setOnClickListener { showAddFriendDialog() }
        btnAddAction.setOnClickListener { showAddFriendDialog() }

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
        btnQuickVoiceCall.setOnClickListener {
            val contactName = contacts.firstOrNull()?.displayName
            if (contactName != null) {
                startCall(contactName, isVideo = false)
            } else {
                Toast.makeText(this, "Vui lòng thêm bạn bè vào danh bạ để gọi thoại", Toast.LENGTH_SHORT).show()
                showAddFriendDialog()
            }
        }
        btnQuickVideoCall.setOnClickListener {
            val contactName = contacts.firstOrNull()?.displayName
            if (contactName != null) {
                startCall(contactName, isVideo = true)
            } else {
                Toast.makeText(this, "Vui lòng thêm bạn bè vào danh bạ để gọi video", Toast.LENGTH_SHORT).show()
                showAddFriendDialog()
            }
        }

        // Tab 4: Profile
        val user = sessionManager.currentUser
        tvProfileDisplayName = findViewById(R.id.tvProfileDisplayName)
        tvProfileEmail = findViewById(R.id.tvProfileEmail)
        tvProfileLargeAvatar = findViewById(R.id.tvProfileLargeAvatar)
        btnToggleTheme = findViewById(R.id.btnToggleTheme)
        tvCurrentThemeLabel = findViewById(R.id.tvCurrentThemeLabel)
        btnAboutDevice = findViewById(R.id.btnAboutDevice)
        btnLogout = findViewById(R.id.btnLogout)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)

        tvProfileDisplayName.text = user.displayName
        tvProfileEmail.text = user.email
        val initial = user.displayName.ifBlank { user.email.substringBefore("@") }.ifBlank { "U" }.take(1).uppercase()
        tvProfileLargeAvatar.text = initial

        updateThemeLabel()

        btnToggleTheme.setOnClickListener {
            showThemeSelectionDialog()
        }

        btnAboutDevice.setOnClickListener {
            showAboutDeviceDialog()
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
        // 1. Contacts
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

        // Online stories (friends from contacts who are online)
        onlineUsers.clear()
        onlineUsers.addAll(contacts)
        storyAdapter.notifyDataSetChanged()
        layoutStoriesRow.visibility = if (onlineUsers.isNotEmpty()) View.VISIBLE else View.GONE

        // 2. Conversations
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

        // 3. Call history
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

    private fun openChat(conversationId: String, title: String) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("EXTRA_CONV_ID", conversationId)
            putExtra("EXTRA_CONV_TITLE", title)
        }
        startActivity(intent)
    }

    private fun startCall(contactName: String, isVideo: Boolean) {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("EXTRA_CONTACT_NAME", contactName)
            putExtra("EXTRA_IS_VIDEO", isVideo)
        }
        startActivity(intent)
    }

    private fun showAddFriendDialog() {
        val input = EditText(this).apply {
            hint = "Nhập email bạn bè (vd: friend@gmail.com)"
            setPadding(44, 32, 44, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Thêm bạn bè mới")
            .setMessage("Nhập email để thêm bạn vào danh bạ và bắt đầu nhắn tin:")
            .setView(input)
            .setPositiveButton("Thêm vào danh bạ") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    val displayName = query.substringBefore("@")
                    val newContact = User(
                        id = "u_${System.currentTimeMillis()}",
                        displayName = displayName,
                        email = query,
                        isOnline = true
                    )
                    val added = appDataStore.addContact(newContact)
                    if (added) {
                        Toast.makeText(this, "Đã thêm $displayName vào danh bạ!", Toast.LENGTH_SHORT).show()
                        refreshData()
                    } else {
                        Toast.makeText(this, "Người dùng này đã có trong danh bạ", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun updateThemeLabel() {
        tvCurrentThemeLabel.text = when (appDataStore.themeMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> "Giao diện Tối"
            AppCompatDelegate.MODE_NIGHT_NO -> "Giao diện Sáng"
            else -> "Theo hệ thống"
        }
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
