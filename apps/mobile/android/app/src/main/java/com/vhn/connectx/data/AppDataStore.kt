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
        private const val KEY_NICKNAMES = "key_contact_nicknames"
        private const val KEY_MUTED_CHATS = "key_muted_chats"

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
        val existingIndex = list.indexOfFirst { it.email.equals(user.email, ignoreCase = true) || it.id == user.id }
        if (existingIndex >= 0) {
            val existing = list[existingIndex]
            val isCurrentSuspect = existing.displayName.isBlank() ||
                    existing.displayName.equals(existing.email.substringBefore("@"), ignoreCase = true)
            val isNewBetter = user.displayName.isNotBlank() &&
                    !user.displayName.equals(user.email.substringBefore("@"), ignoreCase = true)

            if ((isCurrentSuspect && isNewBetter) ||
                (user.displayName.isNotBlank() && user.displayName != existing.displayName)) {
                list[existingIndex] = existing.copy(
                    displayName = user.displayName,
                    avatarUrl = user.avatarUrl.ifBlank { existing.avatarUrl }
                )
                saveContacts(list)
                return true
            }
            return false // Already exists with valid name
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
            val nameCandidate = req.receiverName.ifBlank { req.receiverEmail.substringBefore("@") }
            val safeName = if (nameCandidate.equals(myUser.displayName, ignoreCase = true)) {
                req.receiverEmail.substringBefore("@")
            } else {
                nameCandidate
            }
            User(
                id = "u_${req.receiverEmail.hashCode()}",
                displayName = safeName,
                email = req.receiverEmail,
                isOnline = true
            )
        } else {
            val nameCandidate = req.senderName.ifBlank { req.senderEmail.substringBefore("@") }
            val safeName = if (nameCandidate.equals(myUser.displayName, ignoreCase = true)) {
                req.senderEmail.substringBefore("@")
            } else {
                nameCandidate
            }
            User(
                id = req.senderId.ifBlank { "u_${req.senderEmail.hashCode()}" },
                displayName = safeName,
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
        val finalTitle = if (title.isNotBlank()) {
            title
        } else if (existingIndex >= 0 && list[existingIndex].title.isNotBlank()) {
            list[existingIndex].title
        } else {
            "Trò chuyện"
        }

        val updated = Conversation(
            id = convId,
            title = finalTitle,
            lastMessage = lastMessage,
            timestamp = timestamp,
            unreadCount = 0,
            isGroup = false,
            avatarInitial = finalTitle.ifBlank { "U" }.take(1).uppercase()
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
        val existingIndex = list.indexOfFirst { it.id == message.id }
        if (existingIndex >= 0) {
            list[existingIndex] = message
        } else {
            list.add(message)
        }
        prefs.edit().putString(KEY_MESSAGES_PREFIX + convId, gson.toJson(list)).apply()

        // Preserve existing conversation title so sending an outgoing message does NOT overwrite the title with my own name!
        val convList = getConversations()
        val convIndex = convList.indexOfFirst { it.id == convId }
        val existingTitle = if (convIndex >= 0) convList[convIndex].title else ""
        val titleToUse = if (existingTitle.isNotBlank()) existingTitle else message.senderName

        updateConversation(
            convId = convId,
            title = titleToUse,
            lastMessage = when (message.messageType) {
                "image" -> "📷 [Hình ảnh]"
                "call" -> if (message.isVideoCall) "📹 [Cuộc gọi video]" else "📞 [Cuộc gọi thoại]"
                else -> message.text
            },
            timestamp = message.timestamp
        )
    }

    fun saveMessages(convId: String, list: List<Message>) {
        prefs.edit().putString(KEY_MESSAGES_PREFIX + convId, gson.toJson(list)).apply()
    }

    fun clearMessages(convId: String) {
        prefs.edit().remove(KEY_MESSAGES_PREFIX + convId).apply()
        val list = getConversations()
        val index = list.indexOfFirst { it.id == convId }
        if (index >= 0) {
            val conv = list[index]
            list[index] = conv.copy(lastMessage = "Đã xóa lịch sử trò chuyện", timestamp = "Vừa xong")
            prefs.edit().putString(KEY_CONVERSATIONS, gson.toJson(list)).apply()
        }
    }

    fun getNickname(email: String): String? {
        if (email.isBlank()) return null
        val json = prefs.getString(KEY_NICKNAMES, null) ?: return null
        val type = object : TypeToken<Map<String, String>>() {}.type
        val map: Map<String, String> = try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        return map[email.lowercase().trim()]
    }

    fun setNickname(email: String, nickname: String) {
        if (email.isBlank()) return
        val json = prefs.getString(KEY_NICKNAMES, null)
        val type = object : TypeToken<MutableMap<String, String>>() {}.type
        val map: MutableMap<String, String> = try {
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        if (nickname.isBlank()) {
            map.remove(email.lowercase().trim())
        } else {
            map[email.lowercase().trim()] = nickname.trim()
        }
        prefs.edit().putString(KEY_NICKNAMES, gson.toJson(map)).apply()
    }

    fun isChatMuted(convId: String): Boolean {
        val mutedSet = prefs.getStringSet(KEY_MUTED_CHATS, null) ?: return false
        return mutedSet.contains(convId)
    }

    fun setChatMuted(convId: String, isMuted: Boolean) {
        val currentSet = prefs.getStringSet(KEY_MUTED_CHATS, null)?.toMutableSet() ?: mutableSetOf()
        if (isMuted) {
            currentSet.add(convId)
        } else {
            currentSet.remove(convId)
        }
        prefs.edit().putStringSet(KEY_MUTED_CHATS, currentSet).apply()
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

    fun updateCallHistoryStatus(callId: String, status: String, durationSeconds: Int = 0) {
        val list = getCallHistory()
        val index = list.indexOfFirst { it.id == callId }
        if (index >= 0) {
            val item = list[index]
            val updated = item.copy(
                status = status,
                durationSeconds = if (durationSeconds > 0) durationSeconds else item.durationSeconds
            )
            list[index] = updated
            prefs.edit().putString(KEY_CALL_HISTORY, gson.toJson(list)).apply()
        }
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
