package com.vhn.connectx.model

data class User(
    val id: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String = "",
    val isOnline: Boolean = true
)

data class FriendRequest(
    val id: String,
    val senderId: String,
    val senderName: String,
    val senderEmail: String,
    val receiverEmail: String,
    val timestamp: String,
    val status: String = "PENDING" // "PENDING", "ACCEPTED", "REJECTED"
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
    val isMe: Boolean,
    val mediaUrl: String? = null,
    val messageType: String = "text" // "text", "image", "video", "call", "file"
)

data class CallHistoryItem(
    val id: String,
    val contactName: String,
    val timestamp: String,
    val isVideo: Boolean = false,
    val isIncoming: Boolean = true,
    val status: String = "CONNECTED", // "CONNECTED", "MISSED", "UNREACHABLE", "DECLINED", "CANCELLED"
    val durationSeconds: Int = 0,
    val contactEmail: String = ""
)

data class GroupedCallHistory(
    val contactName: String,
    val contactEmail: String,
    val calls: MutableList<CallHistoryItem> = mutableListOf()
) {
    val latestCall: CallHistoryItem get() = calls.first()
    val totalCount: Int get() = calls.size
    val hasMissedCall: Boolean get() = calls.any { it.status == "MISSED" || it.status == "UNREACHABLE" }
}

