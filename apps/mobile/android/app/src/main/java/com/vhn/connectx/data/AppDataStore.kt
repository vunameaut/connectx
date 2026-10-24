package com.vhn.connectx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vhn.connectx.model.CallHistoryItem
import com.vhn.connectx.model.Conversation
import com.vhn.connectx.model.FriendRequest
import com.vhn.connectx.model.Message
import com.vhn.connectx.model.User

class AppDataStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("connectx_app_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_CONTACTS = "key_contacts"
        private const val KEY_FRIEND_REQUESTS = "key_friend_requests"
        private const val KEY_CONVERSATIONS = "key_conversations"
        private const val KEY_MESSAGES_PREFIX = "key_messages_"
        private const val KEY_CALL_HISTORY = "key_call_history"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_SERVER_API_HOST = "key_server_api_host"

        @Volatile
        private var instance: AppDataStore? = null

        fun getInstance(context: Context): AppDataStore {
            return instance ?: synchronized(this) {
                instance ?: AppDataStore(context.applicationContext).also { instance = it }
            }
        }
    }

    // --- CONTACTS ---
    fun getContacts(): MutableList<User> {
        val json = prefs.getString(KEY_CONTACTS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<User>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addContact(user: User): Boolean {
        val list = getContacts()
        if (list.any { it.email.equals(user.email, ignoreCase = true) || it.id == user.id }) {
            return false // Already exists
        }
        list.add(0, user)
        saveContacts(list)
        return true
    }

    fun removeContact(userId: String) {
        val list = getContacts()
        list.removeAll { it.id == userId }
        saveContacts(list)
    }

    fun clearAllTestData() {
        val editor = prefs.edit()
        editor.remove(KEY_CONTACTS)
        editor.remove(KEY_FRIEND_REQUESTS)
        editor.remove(KEY_CONVERSATIONS)
        editor.remove(KEY_CALL_HISTORY)
        // Clear all conversation messages
        val allKeys = prefs.all.keys
        for (key in allKeys) {
            if (key.startsWith(KEY_MESSAGES_PREFIX)) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    fun saveContacts(list: List<User>) {
        prefs.edit().putString(KEY_CONTACTS, gson.toJson(list)).apply()
    }

    // --- FRIEND REQUESTS ---
    fun getFriendRequests(): MutableList<FriendRequest> {
        val json = prefs.getString(KEY_FRIEND_REQUESTS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<FriendRequest>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveFriendRequests(list: List<FriendRequest>) {
        prefs.edit().putString(KEY_FRIEND_REQUESTS, gson.toJson(list)).apply()
    }

    fun sendFriendRequest(req: FriendRequest): Boolean {
        val list = getFriendRequests()
        val exists = list.any {
            it.senderEmail.equals(req.senderEmail, ignoreCase = true) &&
            it.receiverEmail.equals(req.receiverEmail, ignoreCase = true) &&
            it.status == "PENDING"
        }
        if (exists) return false
        list.add(0, req)
        saveFriendRequests(list)
        return true
    }

    fun acceptFriendRequest(requestId: String, myUser: User): User? {
        val list = getFriendRequests()
        val index = list.indexOfFirst { it.id == requestId }
        if (index == -1) return null

        val req = list[index]
        val updated = req.copy(status = "ACCEPTED")
        list[index] = updated
        saveFriendRequests(list)

        // Determine the other party's user object
        val otherUser = if (req.senderEmail.equals(myUser.email, ignoreCase = true)) {
            User(
                id = "u_${System.currentTimeMillis()}",
                displayName = req.receiverEmail.substringBefore("@"),
                email = req.receiverEmail,
                isOnline = true
            )
        } else {
            User(
                id = req.senderId.ifBlank { "u_${System.currentTimeMillis()}" },
                displayName = req.senderName.ifBlank { req.senderEmail.substringBefore("@") },
                email = req.senderEmail,
                isOnline = true
            )
        }

        // Add to contacts
        addContact(otherUser)

        // Automatically create a welcoming conversation
        val convId = getDirectConvId(myUser.email, otherUser.email)
        updateConversation(
            convId = convId,
            title = otherUser.displayName,
            lastMessage = "Đã trở thành bạn bè! Hãy gửi lời chào.",
            timestamp = "Vừa xong"
        )

        return otherUser
    }

    fun rejectFriendRequest(requestId: String): Boolean {
        val list = getFriendRequests()
        val index = list.indexOfFirst { it.id == requestId }
        if (index == -1) return false
        val req = list[index]
        list[index] = req.copy(status = "REJECTED")
        saveFriendRequests(list)
        return true
    }

    fun getPendingRequestsFor(userEmail: String): List<FriendRequest> {
        return getFriendRequests().filter {
            it.receiverEmail.equals(userEmail, ignoreCase = true) && it.status == "PENDING"
        }
    }

    // Direct Conversation ID helper (alphabetic sort to guarantee both participants use the exact same ID)
    fun getDirectConvId(email1: String, email2: String): String {
        val e1 = email1.trim().lowercase().replace("[^a-zA-Z0-9]".toRegex(), "_")
        val e2 = email2.trim().lowercase().replace("[^a-zA-Z0-9]".toRegex(), "_")
        return if (e1 <= e2) "conv_${e1}__${e2}" else "conv_${e2}__${e1}"
    }

    // --- CONVERSATIONS ---
    fun getConversations(): MutableList<Conversation> {
        val json = prefs.getString(KEY_CONVERSATIONS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Conversation>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun updateConversation(convId: String, title: String, lastMessage: String, timestamp: String) {
        val list = getConversations()
        val existingIndex = list.indexOfFirst { it.id == convId }
        val updated = Conversation(
            id = convId,
            title = title,
            lastMessage = lastMessage,
            timestamp = timestamp,
            unreadCount = 0,
            isGroup = false,
            avatarInitial = title.ifBlank { "U" }.take(1).uppercase()
        )
        if (existingIndex >= 0) {
            list.removeAt(existingIndex)
        }
        list.add(0, updated)
        prefs.edit().putString(KEY_CONVERSATIONS, gson.toJson(list)).apply()
    }

    // --- MESSAGES ---
    fun getMessages(convId: String): MutableList<Message> {
        val json = prefs.getString(KEY_MESSAGES_PREFIX + convId, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Message>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addMessage(convId: String, message: Message) {
        val list = getMessages(convId)
        if (list.any { it.id == message.id }) return
        list.add(message)
        prefs.edit().putString(KEY_MESSAGES_PREFIX + convId, gson.toJson(list)).apply()
        updateConversation(
            convId = convId,
            title = message.senderName,
            lastMessage = if (message.messageType == "image") "📷 [Hình ảnh]" else message.text,
            timestamp = message.timestamp
        )
    }

    fun saveMessages(convId: String, list: List<Message>) {
        prefs.edit().putString(KEY_MESSAGES_PREFIX + convId, gson.toJson(list)).apply()
    }

    // --- CALL HISTORY ---
    fun getCallHistory(): MutableList<CallHistoryItem> {
        val json = prefs.getString(KEY_CALL_HISTORY, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<CallHistoryItem>>() {}.type
        return try {
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun addCallHistory(item: CallHistoryItem) {
        val list = getCallHistory()
        list.add(0, item)
        prefs.edit().putString(KEY_CALL_HISTORY, gson.toJson(list)).apply()
    }

    // --- SERVER API HOST ---
    var customServerHost: String?
        get() = prefs.getString(KEY_SERVER_API_HOST, null)
        set(value) = prefs.edit().putString(KEY_SERVER_API_HOST, value).apply()

    // --- THEME PREFERENCE ---
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
            AppCompatDelegate.setDefaultNightMode(value)
        }
}
