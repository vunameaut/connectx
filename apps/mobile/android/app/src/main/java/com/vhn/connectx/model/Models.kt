package com.vhn.connectx.model

data class User(
    val id: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String = "",
    val isOnline: Boolean = true
)

data class Conversation(
    val id: String,
    val title: String,
    val lastMessage: String,
    val timestamp: String,
    val unreadCount: Int = 0,
    val isGroup: Boolean = false,
    val avatarInitial: String = title.take(1).uppercase()
)

data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: String,
    val isMe: Boolean
)

data class CallHistoryItem(
    val id: String,
    val contactName: String,
    val timestamp: String,
    val isVideo: Boolean = false,
    val isIncoming: Boolean = true
)
