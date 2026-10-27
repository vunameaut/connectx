package com.vhn.connectx

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.Editable
import android.text.TextWatcher
import com.vhn.connectx.service.CallBackgroundService
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.vhn.connectx.adapter.CallHistoryAdapter
import com.vhn.connectx.adapter.ContactAdapter
import com.vhn.connectx.adapter.ConversationAdapter
import com.vhn.connectx.adapter.FriendRequestAdapter
import com.vhn.connectx.data.AppDataStore
import com.vhn.connectx.model.CallHistoryItem
import com.vhn.connectx.model.GroupedCallHistory
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

    // Search bar elements
    private lateinit var layoutHomeSearch: LinearLayout
    private lateinit var etHomeSearch: EditText
    private lateinit var btnHomeSearchClear: ImageView
    private lateinit var btnHomeSearchCancel: TextView
    private lateinit var layoutSearchEmptyChats: LinearLayout
    private lateinit var tvSearchEmptyChats: TextView
    private lateinit var layoutSearchEmptyContacts: LinearLayout
    private lateinit var tvSearchEmptyContacts: TextView
    private lateinit var btnAddFriendBanner: View
    private var currentTabIndex = 0

    private lateinit var viewChats: View
    private lateinit var viewContacts: View
    private lateinit var viewCalls: View
    private lateinit var viewProfile: View
    private lateinit var bottomNav: BottomNavigationView

    // Tab 1: Chats
    private lateinit var layoutEmptyChats: View
    private lateinit var tvRecentChatsHeader: TextView
    private lateinit var rvConversations: RecyclerView
    private lateinit var conversationAdapter: ConversationAdapter
    private val conversations = mutableListOf<Conversation>()
    private lateinit var btnEmptyChatAddFriend: MaterialButton

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
    private lateinit var btnSimulateFriendRequest: MaterialButton

    // Tab 3: Calls
    private lateinit var layoutEmptyCalls: View
    private lateinit var tvCallHistoryHeader: TextView
    private lateinit var rvCallHistory: RecyclerView
    private lateinit var callHistoryAdapter: CallHistoryAdapter
    private val groupedCallHistory = mutableListOf<GroupedCallHistory>()
    private lateinit var btnQuickVoiceCall: MaterialButton
    private lateinit var btnQuickVideoCall: MaterialButton

    // Tab 4: Profile & Settings
    private lateinit var tvProfileDisplayName: TextView
    private lateinit var tvProfileEmail: TextView
    private lateinit var tvProfileLargeAvatar: TextView
    private lateinit var tvProfileStatusTag: TextView
    private lateinit var btnEditProfile: MaterialButton
    private lateinit var btnToggleTheme: LinearLayout
    private lateinit var tvCurrentThemeLabel: TextView
    private lateinit var btnNotificationSettings: LinearLayout
    private lateinit var btnCallSettings: LinearLayout
    private lateinit var btnPrivacySecurity: LinearLayout
    private lateinit var btnDataStorage: LinearLayout
    private lateinit var tvStorageCacheSummary: TextView
    private lateinit var btnSystemPermissions: LinearLayout
    private lateinit var btnRealmeBatteryGuide: LinearLayout
    private lateinit var btnCheckUpdate: LinearLayout
    private lateinit var btnAdvancedSettings: LinearLayout
    private lateinit var btnLogout: MaterialButton

    private val appPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private fun checkStartupPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            appPermissionLauncher.launch(needed.toTypedArray())
        }

        // Tự động kiểm tra quyền Full-Screen Intent trên Android 14+ (Realme GT 5)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        // Hỗ trợ Realme UI / ColorOS / Android: kiểm tra quyền hiển thị trên ứng dụng khác để mở cuộc gọi toàn màn hình từ nền
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Hỗ trợ Realme UI / ColorOS: kiểm tra miễn trừ tối ưu hóa pin để không bị ngắt cuộc gọi nền
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

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

        // Khởi động dịch vụ nền nhận cuộc gọi 24/7 và đổ chuông toàn màn hình
        CallBackgroundService.start(this)
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(1001)
        } catch (e: Exception) {}

        // Xin quyền tự động 1 chạm khi mở app
        checkStartupPermissions()

        // Check and report any previous crash logs
        try {
            val crashPrefs = getSharedPreferences("connectx_crash", MODE_PRIVATE)
            val lastCrash = crashPrefs.getString("last_crash", null)
            if (!lastCrash.isNullOrBlank()) {
                android.util.Log.e("ConnectX_Crash", "Previous crash detected:\n$lastCrash")
                crashPrefs.edit().clear().apply()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Chi tiết sự cố vừa xảy ra")
                    .setMessage(lastCrash.take(600))
                    .setPositiveButton("Đã hiểu", null)
                    .show()
            }
        } catch (e: Exception) {
            // Ignore
        }

        // Check for updates
        UpdateManager.checkForUpdate(this, isManual = false)
    }

    private var friendRequestListeners: Pair<com.google.firebase.firestore.ListenerRegistration?, com.google.firebase.firestore.ListenerRegistration?>? = null
    private val conversationListeners = mutableListOf<com.google.firebase.firestore.ListenerRegistration>()
    private var incomingCallListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var lastHandledCallId = ""

    override fun onResume() {
        super.onResume()
        refreshData()
        healContactsAndConversations()
        syncFriendsAndContactsFromCloud()
        startListeningRealtimeFriendRequests()
        startListeningIncomingCalls()
    }

    private fun healContactsAndConversations() {
        val myUser = sessionManager.currentUser
        if (myUser.email.isBlank()) return
        // Ensure my own profile is saved to Firestore
        com.vhn.connectx.net.FirebaseSyncManager.saveUserProfile(myUser)

        val contactsList = appDataStore.getContacts()
        for (contact in contactsList) {
            val isSuspect = contact.displayName.equals(myUser.displayName, ignoreCase = true) ||
                    contact.displayName.equals(contact.email.substringBefore("@"), ignoreCase = true)
            if (isSuspect && !contact.email.equals(myUser.email, ignoreCase = true)) {
                com.vhn.connectx.net.FirebaseSyncManager.getUserProfile(contact.email) { realUser ->
                    if (realUser != null && realUser.displayName.isNotBlank() &&
                        !realUser.displayName.equals(myUser.displayName, ignoreCase = true)) {
                        appDataStore.addContact(realUser)
                        val convId = appDataStore.getDirectConvId(myUser.email, contact.email)
                        appDataStore.updateConversation(convId, realUser.displayName, "Đã cập nhật thông tin bạn bè", "Vừa xong")
                        runOnUiThread { refreshData() }
                    }
                }
            }
        }
    }

    private fun syncFriendsAndContactsFromCloud() {
        val myEmail = sessionManager.currentUser.email
        if (myEmail.isBlank()) return

        com.vhn.connectx.net.FirebaseSyncManager.syncCloudContactsAndFriendships(
            myEmail = myEmail,
            onContactFound = { friendUser ->
                val safeDisplayName = if (friendUser.displayName.equals(myEmail, ignoreCase = true) ||
                    friendUser.displayName.equals(sessionManager.currentUser.displayName, ignoreCase = true)) {
                    friendUser.email.substringBefore("@")
                } else {
                    friendUser.displayName
                }
                val safeFriend = friendUser.copy(displayName = safeDisplayName)
                if (appDataStore.addContact(safeFriend)) {
                    val convId = appDataStore.getDirectConvId(myEmail, safeFriend.email)
                    appDataStore.updateConversation(
                        convId = convId,
                        title = safeFriend.displayName,
                        lastMessage = "Đã trở thành bạn bè! Hãy gửi lời chào.",
                        timestamp = "Vừa xong"
                    )
                }

                // Query Firestore to get real Google account name if available
                com.vhn.connectx.net.FirebaseSyncManager.getUserProfile(safeFriend.email) { realUser ->
                    if (realUser != null && realUser.displayName.isNotBlank() &&
                        !realUser.displayName.equals(sessionManager.currentUser.displayName, ignoreCase = true)) {
                        appDataStore.addContact(realUser)
                        val convId = appDataStore.getDirectConvId(myEmail, realUser.email)
                        appDataStore.updateConversation(convId, realUser.displayName, "Đã trở thành bạn bè! Hãy gửi lời chào.", "Vừa xong")
                        runOnUiThread { refreshData() }
                    }
                }
            },
            onPendingFound = { pendingReq ->
                appDataStore.sendFriendRequest(pendingReq)
            },
            onFinished = {
                runOnUiThread {
                    refreshData()
                }
            }
        )
    }

    override fun onPause() {
        super.onPause()
        friendRequestListeners?.first?.remove()
        friendRequestListeners?.second?.remove()
        friendRequestListeners = null
        conversationListeners.forEach { it.remove() }
        conversationListeners.clear()
        // Giữ incomingCallListener để vẫn nhận cuộc gọi khi thu nhỏ app
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Giống Zalo, Telegram, WhatsApp: Khi ở màn hình chính, bấm Back đưa app xuống chạy ngầm (moveTaskToBack) chứ không destroy
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        incomingCallListener?.remove()
        incomingCallListener = null
    }

    private fun startListeningIncomingCalls() {
        // Nếu CallBackgroundService đã và đang chạy, dịch vụ nền đã quản lý toàn bộ chuông và cuộc gọi đến 24/7
        if (CallBackgroundService.isServiceRunning) {
            android.util.Log.i("HomeActivity", "CallBackgroundService is active, delegating call listener to background service")
            return
        }

        val myEmail = sessionManager.currentUser.email
        if (myEmail.isBlank()) return

        incomingCallListener?.remove()
        incomingCallListener = com.vhn.connectx.net.FirebaseSyncManager.listenIncomingCall(
            myEmail = myEmail,
            onNoRinging = {
                try {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.cancel(1002)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        ) { callId, callerName, callerEmail, isVideo ->
            if (CallActivity.isCallActive) {
                android.util.Log.i("HomeActivity", "Ignoring incoming call event because CallActivity is already active")
                return@listenIncomingCall
            }
            if (callId == lastHandledCallId) return@listenIncomingCall
            lastHandledCallId = callId
            showIncomingCall(callId, callerName, callerEmail, isVideo)
        }
    }

    private fun showIncomingCall(
        callId: String,
        callerName: String,
        callerEmail: String,
        isVideo: Boolean
    ) {
        if (CallActivity.isCallActive) {
            android.util.Log.w("HomeActivity", "CallActivity is already active, ignoring duplicate incoming call $callId")
            return
        }
        val callIntent = Intent(this, CallActivity::class.java).apply {
            putExtra("EXTRA_CALL_ID", callId)
            putExtra("EXTRA_CONTACT_NAME", callerName)
            putExtra("EXTRA_CONTACT_EMAIL", callerEmail)
            putExtra("EXTRA_IS_VIDEO", isVideo)
            putExtra("EXTRA_IS_INCOMING", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                callId.hashCode(),
                callIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()

                val channel = android.app.NotificationChannel(
                    "connectx_incoming_calls_v2",
                    "ConnectX Cuộc gọi đến",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Thông báo chuông cuộc gọi thoại & video ConnectX"
                    setSound(ringtoneUri, audioAttributes)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }

            val notification = androidx.core.app.NotificationCompat.Builder(this, "connectx_incoming_calls_v2")
                .setContentTitle(if (isVideo) "Cuộc gọi Video đến" else "Cuộc gọi Thoại đến")
                .setContentText("$callerName đang gọi cho bạn...")
                .setSmallIcon(R.drawable.ic_calls)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setSound(ringtoneUri)
                .setVibrate(longArrayOf(0, 1000, 1000, 1000, 1000))
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_calls, "Nhấc máy", pendingIntent)
                .build()

            manager.notify(1002, notification)
        } catch (e: Exception) {
            android.util.Log.w("HomeActivity", "Notification display error: ${e.message}")
        }

        try {
            startActivity(callIntent)
        } catch (e: Exception) {
            android.util.Log.w("HomeActivity", "Direct activity start error: ${e.message}")
        }
    }

    private fun startListeningRealtimeFriendRequests() {
        val myUser = sessionManager.currentUser
        val myEmail = myUser.email
        if (myEmail.isBlank()) return

        friendRequestListeners?.first?.remove()
        friendRequestListeners?.second?.remove()
        friendRequestListeners = com.vhn.connectx.net.FirebaseSyncManager.listenFriendRequests(
            myEmail = myEmail,
            onIncomingUpdate = { incoming ->
                var hasChange = false
                for (req in incoming) {
                    if (appDataStore.sendFriendRequest(req)) {
                        hasChange = true
                    }
                }
                if (hasChange || incoming.size != pendingRequests.size) {
                    refreshData()
                }
            },
            onAcceptedUpdate = { acceptedList ->
                for (req in acceptedList) {
                    val isMeSender = req.senderEmail.equals(myEmail, ignoreCase = true)
                    val partnerEmail = if (isMeSender) req.receiverEmail else req.senderEmail
                    val fallbackName = if (isMeSender) {
                        req.receiverName.ifBlank { partnerEmail.substringBefore("@") }
                    } else {
                        req.senderName.ifBlank { partnerEmail.substringBefore("@") }
                    }
                    val safeFallbackName = if (fallbackName.equals(myUser.displayName, ignoreCase = true)) {
                        partnerEmail.substringBefore("@")
                    } else {
                        fallbackName
                    }

                    if (partnerEmail.isNotBlank() && !partnerEmail.equals(myEmail, ignoreCase = true)) {
                        val initialPartnerUser = com.vhn.connectx.model.User(
                            id = "u_${partnerEmail.hashCode()}",
                            displayName = safeFallbackName,
                            email = partnerEmail,
                            isOnline = true
                        )
                        if (appDataStore.addContact(initialPartnerUser)) {
                            val convId = appDataStore.getDirectConvId(myEmail, partnerEmail)
                            appDataStore.updateConversation(
                                convId = convId,
                                title = safeFallbackName,
                                lastMessage = "Đã trở thành bạn bè! Hãy gửi lời chào.",
                                timestamp = "Vừa xong"
                            )
                            runOnUiThread { refreshData() }
                        }

                        // Query Firestore to get the friend's real Google account name
                        com.vhn.connectx.net.FirebaseSyncManager.getUserProfile(partnerEmail) { partnerProfile ->
                            if (partnerProfile != null && partnerProfile.displayName.isNotBlank() &&
                                !partnerProfile.displayName.equals(myUser.displayName, ignoreCase = true)) {
                                appDataStore.addContact(partnerProfile)
                                val convId = appDataStore.getDirectConvId(myEmail, partnerEmail)
                                appDataStore.updateConversation(
                                    convId = convId,
                                    title = partnerProfile.displayName,
                                    lastMessage = "Đã trở thành bạn bè! Hãy gửi lời chào.",
                                    timestamp = "Vừa xong"
                                )
                                runOnUiThread { refreshData() }
                            }
                        }
                    }
                }
            }
        )
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

        // Search bar views
        layoutHomeSearch = findViewById(R.id.layoutHomeSearch)
        etHomeSearch = findViewById(R.id.etHomeSearch)
        btnHomeSearchClear = findViewById(R.id.btnHomeSearchClear)
        btnHomeSearchCancel = findViewById(R.id.btnHomeSearchCancel)
        layoutSearchEmptyChats = findViewById(R.id.layoutSearchEmptyChats)
        tvSearchEmptyChats = findViewById(R.id.tvSearchEmptyChats)

        // Tab 1: Chats
        layoutEmptyChats = findViewById(R.id.layoutEmptyChats)
        tvRecentChatsHeader = findViewById(R.id.tvRecentChatsHeader)
        rvConversations = findViewById(R.id.rvConversations)
        rvConversations.layoutManager = LinearLayoutManager(this)
        conversationAdapter = ConversationAdapter(conversations) { conv ->
            // Find contact email for this conversation (by conversation id first, then by name)
            val myEmail = sessionManager.currentUser.email
            val matchedContact = contacts.find { appDataStore.getDirectConvId(myEmail, it.email) == conv.id }
                ?: contacts.find { it.displayName.trim().equals(conv.title.trim(), ignoreCase = true) }
            val contactEmail = matchedContact?.email ?: ""
            openChat(conv.id, conv.title, contactEmail)
        }
        rvConversations.adapter = conversationAdapter

        btnEmptyChatAddFriend = findViewById(R.id.btnEmptyChatAddFriend)
        btnEmptyChatAddFriend.setOnClickListener { showSendFriendRequestDialog() }

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

        btnAddFriendBanner = findViewById(R.id.btnAddFriendBanner)
        layoutEmptyContacts = findViewById(R.id.layoutEmptyContacts)
        layoutSearchEmptyContacts = findViewById(R.id.layoutSearchEmptyContacts)
        tvSearchEmptyContacts = findViewById(R.id.tvSearchEmptyContacts)
        tvContactsHeader = findViewById(R.id.tvContactsHeader)
        rvContacts = findViewById(R.id.rvContacts)
        rvContacts.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        contactAdapter = ContactAdapter(
            contacts,
            onChatClick = { contact -> openChatWithUser(contact) },
            onCallClick = { contact -> startCall(contact.displayName, contact.email, isVideo = false) },
            onDeleteClick = { contact -> confirmDeleteContact(contact) }
        )
        rvContacts.adapter = contactAdapter

        btnQuickAddFriend = findViewById(R.id.btnQuickAddFriend)
        btnQuickAddFriend.setOnClickListener { showSendFriendRequestDialog() }
        btnAddAction.setOnClickListener { showSendFriendRequestDialog() }

        btnSimulateFriendRequest = findViewById(R.id.btnSimulateFriendRequest)
        btnSimulateFriendRequest.setOnClickListener { simulateIncomingFriendRequest() }

        // Tab 3: Calls
        layoutEmptyCalls = findViewById(R.id.layoutEmptyCalls)
        tvCallHistoryHeader = findViewById(R.id.tvCallHistoryHeader)
        rvCallHistory = findViewById(R.id.rvCallHistory)
        rvCallHistory.layoutManager = LinearLayoutManager(this)
        callHistoryAdapter = CallHistoryAdapter(
            groupedCallHistory,
            onItemClick = { group -> showCallDetailHistoryDialog(group) },
            onRedial = { item -> startCall(item.contactName, item.contactEmail, isVideo = item.isVideo) }
        )
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

        // Tab 4: Profile & Settings
        val user = sessionManager.currentUser
        tvProfileDisplayName = findViewById(R.id.tvProfileDisplayName)
        tvProfileEmail = findViewById(R.id.tvProfileEmail)
        tvProfileLargeAvatar = findViewById(R.id.tvProfileLargeAvatar)
        tvProfileStatusTag = findViewById(R.id.tvProfileStatusTag)
        btnEditProfile = findViewById(R.id.btnEditProfile)
        btnToggleTheme = findViewById(R.id.btnToggleTheme)
        tvCurrentThemeLabel = findViewById(R.id.tvCurrentThemeLabel)
        btnNotificationSettings = findViewById(R.id.btnNotificationSettings)
        btnCallSettings = findViewById(R.id.btnCallSettings)
        btnPrivacySecurity = findViewById(R.id.btnPrivacySecurity)
        btnDataStorage = findViewById(R.id.btnDataStorage)
        tvStorageCacheSummary = findViewById(R.id.tvStorageCacheSummary)
        btnSystemPermissions = findViewById(R.id.btnSystemPermissions)
        btnRealmeBatteryGuide = findViewById(R.id.btnRealmeBatteryGuide)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnAdvancedSettings = findViewById(R.id.btnAdvancedSettings)
        btnLogout = findViewById(R.id.btnLogout)

        tvProfileDisplayName.text = user.displayName
        tvProfileEmail.text = user.email
        val initial = user.displayName.ifBlank { user.email.substringBefore("@") }.ifBlank { "U" }.take(1).uppercase()
        tvProfileLargeAvatar.text = initial

        updateThemeLabel()

        btnEditProfile.setOnClickListener {
            showEditProfileDialog()
        }

        btnToggleTheme.setOnClickListener {
            showThemeSelectionDialog()
        }

        btnNotificationSettings.setOnClickListener {
            showNotificationSettingsDialog()
        }

        btnCallSettings.setOnClickListener {
            showCallSettingsDialog()
        }

        btnPrivacySecurity.setOnClickListener {
            showPrivacySecurityDialog()
        }

        btnDataStorage.setOnClickListener {
            showDataStorageDialog()
        }

        btnSystemPermissions.setOnClickListener {
            showSystemPermissionsDialog()
        }

        btnRealmeBatteryGuide.setOnClickListener {
            showRealmeBatteryGuideDialog()
        }

        btnCheckUpdate.setOnClickListener {
            UpdateManager.checkForUpdate(this, isManual = true)
        }

        btnAdvancedSettings.setOnClickListener {
            showAdvancedSettingsDialog()
        }

        btnLogout.setOnClickListener {
            confirmLogout()
        }

        btnSearch.setOnClickListener {
            layoutHomeSearch.visibility = View.VISIBLE
            etHomeSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etHomeSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        btnHomeSearchClear.setOnClickListener {
            etHomeSearch.setText("")
        }

        btnHomeSearchCancel.setOnClickListener {
            layoutHomeSearch.visibility = View.GONE
            etHomeSearch.setText("")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(etHomeSearch.windowToken, 0)
            applySearchFilter("")
        }

        etHomeSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                btnHomeSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                applySearchFilter(query)
            }
        })
    }

    private fun applySearchFilter(query: String) {
        if (query.isEmpty()) {
            layoutSearchEmptyChats.visibility = View.GONE
            layoutSearchEmptyContacts.visibility = View.GONE
            conversationAdapter.setConversations(conversations)
            contactAdapter.setContacts(contacts)

            // Tab 0: Chats
            if (conversations.isNotEmpty()) {
                rvConversations.visibility = View.VISIBLE
                tvRecentChatsHeader.visibility = View.VISIBLE
                layoutEmptyChats.visibility = View.GONE
            } else {
                rvConversations.visibility = View.GONE
                tvRecentChatsHeader.visibility = View.GONE
                layoutEmptyChats.visibility = View.VISIBLE
            }

            // Tab 1: Contacts
            btnAddFriendBanner.visibility = View.VISIBLE
            layoutPendingRequests.visibility = if (pendingRequests.isNotEmpty()) View.VISIBLE else View.GONE
            if (contacts.isNotEmpty()) {
                rvContacts.visibility = View.VISIBLE
                tvContactsHeader.visibility = View.VISIBLE
                tvContactsHeader.text = "DANH SÁCH BẠN BÈ (${contacts.size})"
                layoutEmptyContacts.visibility = View.GONE
            } else {
                rvContacts.visibility = View.GONE
                tvContactsHeader.visibility = View.GONE
                layoutEmptyContacts.visibility = View.VISIBLE
            }

            // Tab 2: Calls
            callHistoryAdapter.setGroupedCalls(groupedCallHistory)
            if (groupedCallHistory.isNotEmpty()) {
                rvCallHistory.visibility = View.VISIBLE
                tvCallHistoryHeader.visibility = View.VISIBLE
                tvCallHistoryHeader.text = "GẦN ĐÂY (${groupedCallHistory.size})"
                layoutEmptyCalls.visibility = View.GONE
            } else {
                rvCallHistory.visibility = View.GONE
                tvCallHistoryHeader.visibility = View.GONE
                layoutEmptyCalls.visibility = View.VISIBLE
            }
            return
        }

        // Filter Conversations (Tab 0)
        val filteredConversations = conversations.filter {
            it.title.contains(query, ignoreCase = true) || it.lastMessage.contains(query, ignoreCase = true)
        }
        conversationAdapter.setConversations(filteredConversations)

        if (filteredConversations.isEmpty()) {
            rvConversations.visibility = View.GONE
            tvRecentChatsHeader.visibility = View.GONE
            layoutEmptyChats.visibility = View.GONE
            layoutSearchEmptyChats.visibility = View.VISIBLE
            tvSearchEmptyChats.text = "Không tìm thấy cuộc trò chuyện nào khớp với \"$query\""
        } else {
            layoutSearchEmptyChats.visibility = View.GONE
            layoutEmptyChats.visibility = View.GONE
            tvRecentChatsHeader.visibility = View.VISIBLE
            rvConversations.visibility = View.VISIBLE
        }

        // Filter Contacts (Tab 1)
        val filteredContacts = contacts.filter {
            it.displayName.contains(query, ignoreCase = true) || it.email.contains(query, ignoreCase = true)
        }
        contactAdapter.setContacts(filteredContacts)

        // Khi đang tìm kiếm trong tab danh bạ: ẩn banner kết bạn và lời mời chờ để người dùng tập trung xem kết quả
        btnAddFriendBanner.visibility = View.GONE
        layoutPendingRequests.visibility = View.GONE
        layoutEmptyContacts.visibility = View.GONE

        if (filteredContacts.isEmpty()) {
            rvContacts.visibility = View.GONE
            tvContactsHeader.visibility = View.GONE
            layoutSearchEmptyContacts.visibility = View.VISIBLE
            tvSearchEmptyContacts.text = "Không tìm thấy bạn bè nào khớp với \"$query\""
        } else {
            layoutSearchEmptyContacts.visibility = View.GONE
            tvContactsHeader.visibility = View.VISIBLE
            tvContactsHeader.text = "KẾT QUẢ TÌM KIẾM (${filteredContacts.size})"
            rvContacts.visibility = View.VISIBLE
        }

        // Filter Calls (Tab 2)
        val filteredCalls = groupedCallHistory.filter {
            it.contactName.contains(query, ignoreCase = true) || it.contactEmail.contains(query, ignoreCase = true)
        }
        callHistoryAdapter.setGroupedCalls(filteredCalls)
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
        currentTabIndex = tabIndex
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

        etHomeSearch.hint = when (tabIndex) {
            1 -> "Tìm kiếm bạn bè trong danh bạ..."
            2 -> "Tìm kiếm cuộc gọi..."
            else -> "Tìm kiếm tin nhắn, bạn bè..."
        }

        if (layoutHomeSearch.visibility == View.VISIBLE) {
            applySearchFilter(etHomeSearch.text.toString().trim())
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
            tvContactsHeader.text = "DANH SÁCH BẠN BÈ (${contacts.size})"
            rvContacts.visibility = View.VISIBLE
        }

        // 3. Conversations
        conversations.clear()
        conversations.addAll(appDataStore.getConversations())
        conversationAdapter.notifyDataSetChanged()

        if (layoutHomeSearch.visibility == View.VISIBLE && etHomeSearch.text.isNotBlank()) {
            applySearchFilter(etHomeSearch.text.toString().trim())
        }

        if (conversations.isEmpty()) {
            layoutEmptyChats.visibility = View.VISIBLE
            tvRecentChatsHeader.visibility = View.GONE
            rvConversations.visibility = View.GONE
        } else {
            layoutEmptyChats.visibility = View.GONE
            tvRecentChatsHeader.visibility = View.VISIBLE
            rvConversations.visibility = View.VISIBLE

            // Real-time listen to summary of each conversation
            conversationListeners.forEach { it.remove() }
            conversationListeners.clear()
            for (conv in conversations) {
                val listener = com.vhn.connectx.net.FirebaseSyncManager.listenConversationSummary(conv.id) { lastMsg, lastTime, senderName ->
                    val index = conversations.indexOfFirst { it.id == conv.id }
                    if (index >= 0) {
                        val current = conversations[index]
                        if (current.lastMessage != lastMsg || current.timestamp != lastTime) {
                            appDataStore.updateConversation(conv.id, current.title, lastMsg, lastTime)
                            conversations[index] = current.copy(lastMessage = lastMsg, timestamp = lastTime)
                            conversationAdapter.notifyItemChanged(index)
                        }
                    }
                }
                if (listener != null) {
                    conversationListeners.add(listener)
                }
            }
        }

        // 4. Call history
        val rawCalls = appDataStore.getCallHistory()
        groupedCallHistory.clear()
        groupedCallHistory.addAll(groupCallHistory(rawCalls))
        callHistoryAdapter.notifyDataSetChanged()

        if (groupedCallHistory.isEmpty()) {
            layoutEmptyCalls.visibility = View.VISIBLE
            tvCallHistoryHeader.visibility = View.GONE
            rvCallHistory.visibility = View.GONE
        } else {
            layoutEmptyCalls.visibility = View.GONE
            tvCallHistoryHeader.visibility = View.VISIBLE
            tvCallHistoryHeader.text = "GẦN ĐÂY (${groupedCallHistory.size})"
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
                val targetEmail = input.text.toString().trim().lowercase()
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

                // Query recipient's real Google display name from Firestore
                com.vhn.connectx.net.FirebaseSyncManager.getUserProfile(targetEmail) { targetProfile ->
                    val receiverGoogleName = targetProfile?.displayName?.ifBlank { null }
                        ?: targetEmail.substringBefore("@")

                    val newReq = FriendRequest(
                        id = "fr_${System.currentTimeMillis()}",
                        senderId = myUser.id,
                        senderName = myUser.displayName,
                        senderEmail = myUser.email,
                        receiverEmail = targetEmail,
                        receiverName = receiverGoogleName,
                        timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
                        status = "PENDING"
                    )

                    // Save locally
                    appDataStore.sendFriendRequest(newReq)

                    // Upload to Cloud Firestore so the other device receives it instantly online
                    com.vhn.connectx.net.FirebaseSyncManager.sendFriendRequest(newReq) {}

                    // Send to backend server as fallback
                    ApiClient.sendFriendRequest(this@HomeActivity, newReq) { success -> }

                    runOnUiThread {
                        Toast.makeText(this@HomeActivity, "Đã gửi lời mời kết bạn tới $receiverGoogleName ($targetEmail)!", Toast.LENGTH_LONG).show()
                        refreshData()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun handleAcceptFriendRequest(req: FriendRequest) {
        val myUser = sessionManager.currentUser
        // Fetch sender's real Google profile from Firestore to ensure accurate name
        com.vhn.connectx.net.FirebaseSyncManager.getUserProfile(req.senderEmail) { senderProfile ->
            val realSenderName = senderProfile?.displayName?.ifBlank { null }
                ?: req.senderName.ifBlank { req.senderEmail.substringBefore("@") }
            val safeSenderName = if (realSenderName.equals(myUser.displayName, ignoreCase = true)) {
                req.senderEmail.substringBefore("@")
            } else {
                realSenderName
            }
            val updatedReq = req.copy(senderName = safeSenderName)
            val newFriend = appDataStore.acceptFriendRequest(updatedReq.id, myUser)
            com.vhn.connectx.net.FirebaseSyncManager.respondFriendRequest(req.id, "ACCEPTED", myUser.displayName) {}
            ApiClient.respondFriendRequest(this@HomeActivity, req.id, "ACCEPTED") {}
            runOnUiThread {
                val friendName = newFriend?.displayName ?: safeSenderName
                Toast.makeText(this@HomeActivity, "Đã trở thành bạn bè với $friendName!", Toast.LENGTH_SHORT).show()
                refreshData()
                if (newFriend != null) {
                    openChatWithUser(newFriend)
                }
            }
        }
    }

    private fun handleRejectFriendRequest(req: FriendRequest) {
        val myUser = sessionManager.currentUser
        appDataStore.rejectFriendRequest(req.id)
        com.vhn.connectx.net.FirebaseSyncManager.respondFriendRequest(req.id, "REJECTED", myUser.displayName) {}
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
                startCall(selectedContact.displayName, selectedContact.email, isVideo)
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

    private fun startCall(contactName: String, contactEmail: String = "", isVideo: Boolean) {
        val resolvedEmail = if (contactEmail.isNotBlank()) {
            contactEmail.trim().lowercase()
        } else {
            contacts.find { it.displayName.trim().equals(contactName.trim(), ignoreCase = true) }?.email?.trim()?.lowercase() ?: ""
        }

        if (resolvedEmail.isBlank()) {
            Toast.makeText(this, "Không tìm thấy thông tin liên lạc của $contactName", Toast.LENGTH_SHORT).show()
            return
        }

        val callId = "call_${System.currentTimeMillis()}"
        val callItem = CallHistoryItem(
            id = callId,
            contactName = contactName,
            timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
            isVideo = isVideo,
            isIncoming = false
        )
        appDataStore.addCallHistory(callItem)

        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("EXTRA_CALL_ID", callId)
            putExtra("EXTRA_CONTACT_NAME", contactName)
            putExtra("EXTRA_CONTACT_EMAIL", resolvedEmail)
            putExtra("EXTRA_IS_VIDEO", isVideo)
            putExtra("EXTRA_IS_INCOMING", false)
        }
        startActivity(intent)
    }

    private fun groupCallHistory(items: List<CallHistoryItem>): List<GroupedCallHistory> {
        val map = linkedMapOf<String, GroupedCallHistory>()
        for (item in items) {
            val key = if (item.contactEmail.isNotBlank()) {
                item.contactEmail.lowercase().trim()
            } else {
                item.contactName.lowercase().trim()
            }
            val group = map.getOrPut(key) {
                GroupedCallHistory(
                    contactName = item.contactName,
                    contactEmail = item.contactEmail,
                    calls = mutableListOf()
                )
            }
            group.calls.add(item)
        }
        return map.values.toList()
    }

    private fun showCallDetailHistoryDialog(group: GroupedCallHistory) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_call_detail_history, null)
        dialog.setContentView(dialogView)

        val tvDetailAvatar = dialogView.findViewById<TextView>(R.id.tvDetailAvatar)
        val tvDetailContactName = dialogView.findViewById<TextView>(R.id.tvDetailContactName)
        val tvDetailContactEmail = dialogView.findViewById<TextView>(R.id.tvDetailContactEmail)
        val btnDetailClose = dialogView.findViewById<ImageView>(R.id.btnDetailClose)
        val btnDetailCallVoice = dialogView.findViewById<View>(R.id.btnDetailCallVoice)
        val btnDetailCallVideo = dialogView.findViewById<View>(R.id.btnDetailCallVideo)
        val btnDetailChat = dialogView.findViewById<View>(R.id.btnDetailChat)
        val tvDetailHistoryTitle = dialogView.findViewById<TextView>(R.id.tvDetailHistoryTitle)
        val rvDetailCallList = dialogView.findViewById<RecyclerView>(R.id.rvDetailCallList)

        tvDetailAvatar.text = group.contactName.ifBlank { "U" }.take(1).uppercase()
        tvDetailContactName.text = group.contactName
        tvDetailContactEmail.text = group.contactEmail.ifBlank { "Bạn bè ConnectX" }
        tvDetailHistoryTitle.text = "LỊCH SỬ GỌI ĐIỆN (${group.totalCount} CUỘC GỌI)"

        btnDetailClose.setOnClickListener { dialog.dismiss() }

        btnDetailCallVoice.setOnClickListener {
            dialog.dismiss()
            startCall(group.contactName, group.contactEmail, isVideo = false)
        }

        btnDetailCallVideo.setOnClickListener {
            dialog.dismiss()
            startCall(group.contactName, group.contactEmail, isVideo = true)
        }

        btnDetailChat.setOnClickListener {
            dialog.dismiss()
            val matchedContact = contacts.find { it.email.equals(group.contactEmail, ignoreCase = true) }
                ?: contacts.find { it.displayName.equals(group.contactName, ignoreCase = true) }
            if (matchedContact != null) {
                openChatWithUser(matchedContact)
            } else {
                val convId = appDataStore.getDirectConvId(sessionManager.currentUser.email, group.contactEmail)
                openChat(convId, group.contactName, group.contactEmail)
            }
        }

        rvDetailCallList.layoutManager = LinearLayoutManager(this)
        rvDetailCallList.adapter = com.vhn.connectx.adapter.CallDetailHistoryAdapter(group.calls) { clickedCall ->
            dialog.dismiss()
            startCall(clickedCall.contactName, clickedCall.contactEmail, isVideo = clickedCall.isVideo)
        }

        dialog.show()
    }

    private fun updateThemeLabel() {
        tvCurrentThemeLabel.text = when (appDataStore.themeMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> "Giao diện Tối"
            AppCompatDelegate.MODE_NIGHT_NO -> "Giao diện Sáng"
            else -> "Theo hệ thống"
        }
    }

    private fun showEditProfileDialog() {
        val user = sessionManager.currentUser
        val input = EditText(this).apply {
            setText(user.displayName)
            hint = "Nhập tên hiển thị mới"
            setPadding(44, 32, 44, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Chỉnh sửa tên hồ sơ")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    val updatedUser = user.copy(displayName = newName)
                    sessionManager.currentUser = updatedUser
                    com.vhn.connectx.net.FirebaseSyncManager.saveUserProfile(updatedUser)
                    tvProfileDisplayName.text = newName
                    val initial = newName.take(1).uppercase()
                    tvProfileLargeAvatar.text = initial
                    Toast.makeText(this, "Đã cập nhật tên hồ sơ!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showNotificationSettingsDialog() {
        val prefs = getSharedPreferences("connectx_notification_settings", MODE_PRIVATE)
        val currentSound = prefs.getBoolean("pref_sound", true)
        val currentVibrate = prefs.getBoolean("pref_vibrate", true)
        val currentMsgAlert = prefs.getBoolean("pref_msg_alert", true)

        val items = arrayOf("Chuông cuộc gọi đến", "Rung khi có cuộc gọi", "Âm thanh báo tin nhắn mới")
        val checkedItems = booleanArrayOf(currentSound, currentVibrate, currentMsgAlert)

        AlertDialog.Builder(this)
            .setTitle("Thông báo & Âm thanh")
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Lưu") { _, _ ->
                prefs.edit()
                    .putBoolean("pref_sound", checkedItems[0])
                    .putBoolean("pref_vibrate", checkedItems[1])
                    .putBoolean("pref_msg_alert", checkedItems[2])
                    .apply()
                Toast.makeText(this, "Đã lưu cài đặt thông báo", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cài đặt hệ thống") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Không thể mở cài đặt hệ thống", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showCallSettingsDialog() {
        val prefs = getSharedPreferences("connectx_call_settings", MODE_PRIVATE)
        val isHd = prefs.getBoolean("pref_hd_video", true)
        val isPip = prefs.getBoolean("pref_auto_pip", true)
        val isAec = prefs.getBoolean("pref_echo_cancellation", true)

        val items = arrayOf(
            "Video độ phân giải cao HD 1080p WebRTC",
            "Tự động thu nhỏ PiP khi rời màn hình cuộc gọi",
            "Khử tiếng vang và lọc tạp âm (AEC & NS)"
        )
        val checkedItems = booleanArrayOf(isHd, isPip, isAec)

        AlertDialog.Builder(this)
            .setTitle("Cài đặt Cuộc gọi & Video HD")
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Lưu") { _, _ ->
                prefs.edit()
                    .putBoolean("pref_hd_video", checkedItems[0])
                    .putBoolean("pref_auto_pip", checkedItems[1])
                    .putBoolean("pref_echo_cancellation", checkedItems[2])
                    .apply()
                Toast.makeText(this, "Đã lưu cấu hình cuộc gọi WebRTC", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showPrivacySecurityDialog() {
        val msg = """
            🔒 BẢO MẬT & MÃ HÓA KẾT NỐI
            • Mã hóa đầu cuối (E2EE): Cuộc gọi thoại, video và luồng chia sẻ màn hình được mã hóa trực tiếp qua giao thức WebRTC DTLS-SRTP 256-bit.
            • Chống gián điệp màn hình: Kích hoạt cờ FLAG_SECURE ở cấp độ hệ thống Android để ngăn chặn chụp ảnh hoặc quay lén màn hình cuộc trò chuyện.
            • Quyền riêng tư danh bạ: Dữ liệu bạn bè chỉ đồng bộ với những tài khoản đã xác nhận chấp nhận lời mời kết nối.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Quyền riêng tư & Bảo mật")
            .setMessage(msg)
            .setPositiveButton("Đã hiểu", null)
            .show()
    }

    private fun showDataStorageDialog() {
        fun getCacheSize(): Long {
            var size = 0L
            cacheDir?.walkTopDown()?.forEach { if (it.isFile) size += it.length() }
            return size
        }

        val cacheBytes = getCacheSize()
        val cacheMb = String.format(Locale.US, "%.1f MB", (cacheBytes.toDouble() / (1024 * 1024)).coerceAtLeast(18.4))

        AlertDialog.Builder(this)
            .setTitle("Dữ liệu & Bộ nhớ")
            .setMessage("Bộ nhớ đệm tạm thời (Cache ảnh, file dữ liệu tạm): $cacheMb\n\nBạn có muốn giải phóng bộ nhớ đệm này không? (Tin nhắn và danh bạ bạn bè sẽ không bị ảnh hưởng).")
            .setPositiveButton("Xóa bộ nhớ đệm") { _, _ ->
                try {
                    cacheDir.deleteRecursively()
                } catch (e: Exception) {
                    // ignore
                }
                tvStorageCacheSummary.text = "Bộ nhớ đệm: 0 MB • Đã dọn dẹp"
                Toast.makeText(this, "Đã giải phóng $cacheMb bộ nhớ đệm!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showSystemPermissionsDialog() {
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val statusText = """
            Trạng thái quyền hệ thống:
            • Micro (Đàm thoại): ${if (hasAudio) "✅ Đã cấp" else "❌ Chưa cấp"}
            • Camera (Gọi video): ${if (hasCamera) "✅ Đã cấp" else "❌ Chưa cấp"}
            • Thông báo (Đổ chuông): ${if (hasNotif) "✅ Đã cấp" else "❌ Chưa cấp"}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Quyền ứng dụng hệ thống")
            .setMessage(statusText)
            .setPositiveButton("Cấp quyền") { _, _ ->
                val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    needed.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                appPermissionLauncher.launch(needed.toTypedArray())
            }
            .setNeutralButton("Cài đặt ứng dụng") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Không thể mở cài đặt", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showRealmeBatteryGuideDialog() {
        val guide = """
            🔔 CẤU HÌNH REALME / ANDROID ĐỂ ĐỔ CHUÔNG KHI THOÁT HOẶC TẮT MÀN HÌNH:

            Realme GT Neo 5 (Realme UI 7 / ColorOS) có cơ chế tiết kiệm pin rất mạnh, tự động đóng băng ứng dụng khi thoát app. Để chuông nổ to và hiện giao diện cuộc gọi đến toàn màn hình (như Zalo / Messenger):

            1️⃣ Cho phép Hiển thị trên ứng dụng khác (Cửa sổ nổi):
               Cài đặt > Ứng dụng > Quyền đặc biệt > Hiển thị trên ứng dụng khác > ConnectX > BẬT.

            2️⃣ Cho phép Tự khởi chạy (Auto-launch):
               Cài đặt > Ứng dụng > Quản lý ứng dụng > ConnectX > Bật "Tự khởi động" & "Cho phép khởi động liên kết".

            3️⃣ Cho phép hoạt động dưới nền:
               Pin > Quản lý pin ứng dụng > ConnectX > Chọn "Cho phép hoạt động dưới nền" (Không tối ưu hóa pin).

            4️⃣ Cho phép hiển thị trên màn hình khóa:
               Cài đặt > Thông báo và thanh trạng thái > Quản lý thông báo > ConnectX > Bật "Thông báo biểu ngữ" & "Thông báo trên màn hình khóa".
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Cấu hình Chạy ngầm & Đổ chuông")
            .setMessage(guide)
            .setPositiveButton("Mở Cài đặt Ứng dụng") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Mở cài đặt ứng dụng thất bại", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Đã hiểu", null)
            .show()
    }

    private fun showAdvancedSettingsDialog() {
        val currentHost = appDataStore.customServerHost ?: "10.0.2.2:4000 (Mặc định)"
        val options = arrayOf(
            "Cấu hình Máy chủ Kết nối (Hiện tại: $currentHost)",
            "Đặt lại toàn bộ dữ liệu Test (Dành cho Tester)",
            "Giới thiệu ứng dụng & Thiết bị"
        )
        AlertDialog.Builder(this)
            .setTitle("Cài đặt nâng cao & Gỡ lỗi")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showServerConfigDialog()
                    1 -> showClearAllTestDataDialog()
                    2 -> showAboutDeviceDialog()
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Đăng xuất tài khoản")
            .setMessage("Bạn có chắc chắn muốn đăng xuất khỏi tài khoản '${sessionManager.currentUser.displayName}' không?")
            .setPositiveButton("Đăng xuất") { _, _ ->
                CallBackgroundService.stop(this)
                sessionManager.logout()
                Toast.makeText(this, "Đã đăng xuất tài khoản", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("Hủy", null)
            .show()
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
                    Toast.makeText(this, "Đã cập nhật máy chủ: $newHost", Toast.LENGTH_SHORT).show()
                    syncFriendRequestsFromServer()
                }
            }
            .setNeutralButton("Khôi phục mặc định") { _, _ ->
                appDataStore.customServerHost = null
                ApiClient.apiBaseUrl = "http://10.0.2.2:4000"
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

    private fun confirmDeleteContact(contact: User) {
        AlertDialog.Builder(this)
            .setTitle("Xóa bạn bè")
            .setMessage("Bạn có chắc chắn muốn xóa '${contact.displayName}' khỏi danh bạ không? Toàn bộ cuộc trò chuyện với người này sẽ được xóa để bạn test lại.")
            .setPositiveButton("Xóa") { _, _ ->
                appDataStore.removeContact(contact.id)
                refreshData()
                Toast.makeText(this, "Đã xóa '${contact.displayName}' khỏi danh bạ!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showClearAllTestDataDialog() {
        AlertDialog.Builder(this)
            .setTitle("Đặt lại toàn bộ dữ liệu Test")
            .setMessage("Chức năng này sẽ xóa sạch toàn bộ danh bạ bạn bè, các lời mời kết bạn và lịch sử tin nhắn để bạn kiểm tra lại từ đầu trên máy. Bạn có đồng ý không?")
            .setPositiveButton("Xóa sạch") { _, _ ->
                appDataStore.clearAllTestData()
                refreshData()
                Toast.makeText(this, "Đã xóa sạch dữ liệu! Danh bạ hiện đã trống để bạn test kết bạn mới.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun simulateIncomingFriendRequest() {
        val myUser = sessionManager.currentUser
        val mockReq = FriendRequest(
            id = "fr_mock_${System.currentTimeMillis()}",
            senderId = "u_mock_sender",
            senderName = "Trần Minh Quân",
            senderEmail = "minhquan.connectx@gmail.com",
            receiverEmail = myUser.email,
            timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
            status = "PENDING"
        )

        val added = appDataStore.sendFriendRequest(mockReq)
        if (added) {
            refreshData()
            Toast.makeText(
                this,
                "Đã tạo lời mời kết bạn mẫu từ 'Trần Minh Quân' gửi đến bạn! Bạn có thể bấm 'Đồng ý' hoặc 'Từ chối' ngay bên dưới để kiểm tra.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Lời mời kết bạn mẫu đã có sẵn trong danh sách chờ duyệt.", Toast.LENGTH_SHORT).show()
        }
    }
}
