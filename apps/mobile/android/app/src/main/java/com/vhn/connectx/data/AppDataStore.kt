package com.vhn.connectx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vhn.connectx.model.CallHistoryItem
import com.vhn.connectx.model.Conversation
import com.vhn.connectx.model.Message
import com.vhn.connectx.model.User

class AppDataStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("connectx_app_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_CONTACTS = "key_contacts"
        private const val KEY_CONVERSATIONS = "key_conversations"
        private const val KEY_MESSAGES_PREFIX = "key_messages_"
        private const val KEY_CALL_HISTORY = "key_call_history"
        private const val KEY_THEME_MODE = "key_theme_mode"

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

    private fun saveContacts(list: List<User>) {
        prefs.edit().putString(KEY_CONTACTS, gson.toJson(list)).apply()
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
        list.add(message)
        prefs.edit().putString(KEY_MESSAGES_PREFIX + convId, gson.toJson(list)).apply()
        updateConversation(
            convId = convId,
            title = message.senderName,
            lastMessage = message.text,
            timestamp = message.timestamp
        )
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

    // --- THEME PREFERENCE ---
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
            AppCompatDelegate.setDefaultNightMode(value)
        }
}
