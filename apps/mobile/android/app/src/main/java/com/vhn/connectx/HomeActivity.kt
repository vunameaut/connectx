package com.vhn.connectx

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.vhn.connectx.adapter.CallHistoryAdapter
import com.vhn.connectx.adapter.ContactAdapter
import com.vhn.connectx.adapter.ConversationAdapter
import com.vhn.connectx.adapter.StoryAdapter
import com.vhn.connectx.model.CallHistoryItem
import com.vhn.connectx.model.Conversation
import com.vhn.connectx.model.SessionManager
import com.vhn.connectx.model.User
import com.vhn.connectx.update.UpdateManager

class HomeActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    // Views
    private lateinit var tvHeaderTitle: TextView
    private lateinit var btnSearch: ImageView
    private lateinit var btnAddAction: ImageView

    private lateinit var viewChats: LinearLayout
    private lateinit var viewContacts: LinearLayout
    private lateinit var viewCalls: LinearLayout
    private lateinit var viewProfile: LinearLayout
    private lateinit var bottomNav: BottomNavigationView

    // Tab 1: Chats
    private lateinit var rvOnlineStories: RecyclerView
    private lateinit var storyAdapter: StoryAdapter
    private val onlineUsers = mutableListOf<User>()

    private lateinit var rvConversations: RecyclerView
    private lateinit var conversationAdapter: ConversationAdapter
    private val conversations = mutableListOf<Conversation>()

    // Tab 2: Contacts
    private lateinit var rvContacts: RecyclerView
    private lateinit var contactAdapter: ContactAdapter
    private val contacts = mutableListOf<User>()
    private lateinit var btnQuickAddFriend: MaterialButton

    // Tab 3: Calls
    private lateinit var rvCallHistory: RecyclerView
    private lateinit var callHistoryAdapter: CallHistoryAdapter
    private val callHistory = mutableListOf<CallHistoryItem>()
    private lateinit var btnQuickVoiceCall: MaterialButton
    private lateinit var btnQuickVideoCall: MaterialButton

    // Tab 4: Profile
    private lateinit var tvProfileDisplayName: TextView
    private lateinit var tvProfileEmail: TextView
    private lateinit var tvProfileLargeAvatar: TextView
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnCheckUpdate: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)

        initViews()
        setupBottomNav()
        loadInitialData()

        // Check for updates in background quietly
        UpdateManager.checkForUpdate(this, isManual = false)
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

        val user = sessionManager.currentUser

        // Tab 1: Chats
        rvOnlineStories = findViewById(R.id.rvOnlineStories)
        rvOnlineStories.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        storyAdapter = StoryAdapter(onlineUsers) { user ->
            openChat("c_${user.id}", user.displayName)
        }
        rvOnlineStories.adapter = storyAdapter

        rvConversations = findViewById(R.id.rvConversations)
        rvConversations.layoutManager = LinearLayoutManager(this)
        conversationAdapter = ConversationAdapter(conversations) { conv ->
            openChat(conv.id, conv.title)
        }
        rvConversations.adapter = conversationAdapter

        // Tab 2
        rvContacts = findViewById(R.id.rvContacts)
        rvContacts.layoutManager = LinearLayoutManager(this)
        contactAdapter = ContactAdapter(
            contacts,
            onChatClick = { contact -> openChat(contact.id, contact.displayName) },
            onCallClick = { contact -> startCall(contact.displayName, isVideo = false) }
        )
        rvContacts.adapter = contactAdapter
        btnQuickAddFriend = findViewById(R.id.btnQuickAddFriend)
        btnQuickAddFriend.setOnClickListener { showAddFriendDialog() }
        btnAddAction.setOnClickListener { showAddFriendDialog() }

        // Tab 3
        rvCallHistory = findViewById(R.id.rvCallHistory)
        rvCallHistory.layoutManager = LinearLayoutManager(this)
        callHistoryAdapter = CallHistoryAdapter(callHistory) { item ->
            startCall(item.contactName, item.isVideo)
        }
        rvCallHistory.adapter = callHistoryAdapter

        btnQuickVoiceCall = findViewById(R.id.btnQuickVoiceCall)
        btnQuickVideoCall = findViewById(R.id.btnQuickVideoCall)
        btnQuickVoiceCall.setOnClickListener {
            val contactName = contacts.firstOrNull()?.displayName ?: "Bạn bè ConnectX"
            startCall(contactName, isVideo = false)
        }
        btnQuickVideoCall.setOnClickListener {
            val contactName = contacts.firstOrNull()?.displayName ?: "Bạn bè ConnectX"
            startCall(contactName, isVideo = true)
        }

        // Tab 4: Profile
        tvProfileDisplayName = findViewById(R.id.tvProfileDisplayName)
        tvProfileEmail = findViewById(R.id.tvProfileEmail)
        tvProfileLargeAvatar = findViewById(R.id.tvProfileLargeAvatar)
        btnLogout = findViewById(R.id.btnLogout)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)

        tvProfileDisplayName.text = user.displayName
        tvProfileEmail.text = user.email
        tvProfileLargeAvatar.text = user.displayName.take(1).uppercase()

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
            Toast.makeText(this, "Tìm kiếm tin nhắn, bạn bè & hội thoại...", Toast.LENGTH_SHORT).show()
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

    private fun loadInitialData() {
        // Active online friends for horizontal story row
        onlineUsers.clear()
        onlineUsers.add(User("u1", "Nguyễn Văn An", "an.nguyen@example.com"))
        onlineUsers.add(User("u2", "Trần Thu Hà", "ha.tran@example.com"))
        onlineUsers.add(User("u3", "Lê Minh Trí", "tri.le@example.com"))
        onlineUsers.add(User("u4", "Phạm Quốc Bảo", "bao.pham@example.com"))
        onlineUsers.add(User("u5", "Hoàng Kim Ngân", "ngan.hoang@example.com"))
        onlineUsers.add(User("u6", "Đặng Minh Quân", "quan.dang@example.com"))
        storyAdapter.notifyDataSetChanged()

        // Mock sample initial chat conversations
        conversations.clear()
        conversations.add(Conversation("c1", "Nguyễn Văn An", "Alo bạn ơi, lát họp chia sẻ màn hình dự án nhé!", "14:35", 2))
        conversations.add(Conversation("c2", "Trần Thu Hà", "File tài liệu đính kèm mình đã gửi rồi đấy.", "12:10", 0))
        conversations.add(Conversation("c3", "Nhóm Kỹ Thuật ConnectX", "Kiểm thử WebRTC & Turn Metered STUN chạy mượt mà.", "Hôm qua", 0, isGroup = true))
        conversations.add(Conversation("c4", "Lê Minh Trí", "Cuộc gọi video chất lượng 1080p nét lắm.", "22/10", 0))
        conversationAdapter.notifyDataSetChanged()

        // Mock sample contacts
        contacts.clear()
        contacts.add(User("u1", "Nguyễn Văn An", "an.nguyen@example.com"))
        contacts.add(User("u2", "Trần Thu Hà", "ha.tran@example.com"))
        contacts.add(User("u3", "Lê Minh Trí", "tri.le@example.com"))
        contacts.add(User("u4", "Phạm Quốc Bảo", "bao.pham@example.com"))
        contactAdapter.notifyDataSetChanged()

        // Mock call history
        callHistory.clear()
        callHistory.add(CallHistoryItem("h1", "Nguyễn Văn An", "14:20 (03:45)", isVideo = true, isIncoming = true))
        callHistory.add(CallHistoryItem("h2", "Trần Thu Hà", "Hôm qua (01:12)", isVideo = false, isIncoming = false))
        callHistory.add(CallHistoryItem("h3", "Lê Minh Trí", "21/10 (15:30)", isVideo = true, isIncoming = true))
        callHistoryAdapter.notifyDataSetChanged()
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
            hint = "Nhập email hoặc tên bạn bè (vd: banbe@gmail.com)"
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Kết bạn mới")
            .setMessage("Nhập email người dùng ConnectX để gửi lời mời kết bạn:")
            .setView(input)
            .setPositiveButton("Gửi kết bạn") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) {
                    val newFriend = User("u_${System.currentTimeMillis()}", query.substringBefore("@"), query)
                    contacts.add(0, newFriend)
                    contactAdapter.notifyItemInserted(0)
                    Toast.makeText(this, "Đã gửi lời mời kết bạn tới: $query", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}
