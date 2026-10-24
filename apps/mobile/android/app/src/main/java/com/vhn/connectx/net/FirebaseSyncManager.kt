package com.vhn.connectx.net

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.vhn.connectx.model.FriendRequest
import com.vhn.connectx.model.Message

object FirebaseSyncManager {

    private const val TAG = "FirebaseSyncManager"
    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    /**
     * Send Friend Request to Cloud Firestore so the recipient receives it instantly online.
     */
    fun sendFriendRequest(req: FriendRequest, onComplete: (Boolean) -> Unit) {
        val data = hashMapOf(
            "id" to req.id,
            "senderId" to req.senderId,
            "senderName" to req.senderName,
            "senderEmail" to req.senderEmail.trim().lowercase(),
            "receiverEmail" to req.receiverEmail.trim().lowercase(),
            "timestamp" to req.timestamp,
            "status" to req.status,
            "createdTime" to System.currentTimeMillis()
        )

        firestore.collection("friend_requests")
            .document(req.id)
            .set(data)
            .addOnSuccessListener {
                Log.i(TAG, "Friend request successfully uploaded to Firestore: ${req.id}")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to upload friend request to Firestore: ${e.message}")
                onComplete(false)
            }
    }

    /**
     * Listen in REAL-TIME for incoming friend requests AND accepted friend requests.
     */
    fun listenFriendRequests(
        myEmail: String,
        onIncomingUpdate: (List<FriendRequest>) -> Unit,
        onAcceptedUpdate: (List<FriendRequest>) -> Unit
    ): Pair<ListenerRegistration?, ListenerRegistration?> {
        val cleanEmail = myEmail.trim().lowercase()
        if (cleanEmail.isBlank()) return Pair(null, null)

        // 1. Where I am receiver:
        // - status == PENDING -> Incoming request to be accepted/declined
        // - status == ACCEPTED -> Previously accepted friendship, partner is the sender
        val incomingListener = try {
            firestore.collection("friend_requests")
                .whereEqualTo("receiverEmail", cleanEmail)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Friend requests listen failed: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        val pendingList = mutableListOf<FriendRequest>()
                        val acceptedList = mutableListOf<FriendRequest>()
                        for (doc in snapshot.documents) {
                            val id = doc.getString("id") ?: doc.id
                            val senderId = doc.getString("senderId") ?: ""
                            val senderName = doc.getString("senderName") ?: ""
                            val senderEmail = doc.getString("senderEmail") ?: ""
                            val receiverEmail = doc.getString("receiverEmail") ?: ""
                            val timestamp = doc.getString("timestamp") ?: ""
                            val status = doc.getString("status") ?: "PENDING"

                            val req = FriendRequest(
                                id = id,
                                senderId = senderId,
                                senderName = senderName,
                                senderEmail = senderEmail,
                                receiverEmail = receiverEmail,
                                timestamp = timestamp,
                                status = status
                            )

                            if (status == "PENDING") {
                                pendingList.add(req)
                            } else if (status == "ACCEPTED") {
                                acceptedList.add(req)
                            }
                        }
                        onIncomingUpdate(pendingList)
                        if (acceptedList.isNotEmpty()) {
                            onAcceptedUpdate(acceptedList)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register incoming friend requests listener", e)
            null
        }

        // 2. Where I am sender:
        // - status == ACCEPTED -> Partner (receiver) accepted my request!
        val acceptedListener = try {
            firestore.collection("friend_requests")
                .whereEqualTo("senderEmail", cleanEmail)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Accepted requests listen failed: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        val acceptedList = mutableListOf<FriendRequest>()
                        for (doc in snapshot.documents) {
                            val id = doc.getString("id") ?: doc.id
                            val senderId = doc.getString("senderId") ?: ""
                            val senderEmail = doc.getString("senderEmail") ?: ""
                            val receiverEmail = doc.getString("receiverEmail") ?: ""
                            val receiverName = doc.getString("receiverName") ?: receiverEmail.substringBefore("@")
                            val timestamp = doc.getString("timestamp") ?: ""
                            val status = doc.getString("status") ?: "PENDING"

                            if (status == "ACCEPTED") {
                                acceptedList.add(
                                    FriendRequest(
                                        id = id,
                                        senderId = senderId,
                                        senderName = receiverName,
                                        senderEmail = senderEmail,
                                        receiverEmail = receiverEmail,
                                        timestamp = timestamp,
                                        status = status
                                    )
                                )
                            }
                        }
                        if (acceptedList.isNotEmpty()) {
                            onAcceptedUpdate(acceptedList)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register accepted requests listener", e)
            null
        }

        return Pair(incomingListener, acceptedListener)
    }

    /**
     * Đồng bộ toàn bộ danh bạ bạn bè và lời mời kết bạn từ Cloud Firestore.
     * Đảm bảo dù gỡ cài đặt app, đổi máy hay xóa dữ liệu thì đăng nhập lại vẫn phục hồi 100% bạn bè.
     */
    fun syncCloudContactsAndFriendships(
        myEmail: String,
        onContactFound: (com.vhn.connectx.model.User) -> Unit,
        onPendingFound: (FriendRequest) -> Unit,
        onFinished: () -> Unit
    ) {
        val cleanEmail = myEmail.trim().lowercase()
        if (cleanEmail.isBlank()) {
            onFinished()
            return
        }

        var remainingTasks = 3
        val checkDone = {
            remainingTasks--
            if (remainingTasks <= 0) {
                onFinished()
            }
        }

        // 1. Đồng bộ từ subcollection contacts
        firestore.collection("users").document(cleanEmail).collection("contacts")
            .get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    val email = doc.getString("email") ?: doc.id
                    val displayName = doc.getString("displayName") ?: email.substringBefore("@")
                    val id = doc.getString("id") ?: "u_${email.hashCode()}"
                    if (email.isNotBlank() && !email.equals(cleanEmail, ignoreCase = true)) {
                        onContactFound(com.vhn.connectx.model.User(id = id, displayName = displayName, email = email, isOnline = true))
                    }
                }
                checkDone()
            }
            .addOnFailureListener { checkDone() }

        // 2. Đồng bộ các lời mời mà tôi là người nhận (receiver)
        firestore.collection("friend_requests")
            .whereEqualTo("receiverEmail", cleanEmail)
            .get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    val status = doc.getString("status") ?: "PENDING"
                    val senderEmail = doc.getString("senderEmail")?.trim()?.lowercase() ?: ""
                    val senderName = doc.getString("senderName") ?: senderEmail.substringBefore("@")
                    val senderId = doc.getString("senderId") ?: "u_${senderEmail.hashCode()}"
                    val id = doc.getString("id") ?: doc.id
                    val timestamp = doc.getString("timestamp") ?: ""

                    if (status == "ACCEPTED" && senderEmail.isNotBlank()) {
                        onContactFound(com.vhn.connectx.model.User(id = senderId, displayName = senderName, email = senderEmail, isOnline = true))
                    } else if (status == "PENDING" && senderEmail.isNotBlank()) {
                        onPendingFound(
                            FriendRequest(
                                id = id,
                                senderId = senderId,
                                senderName = senderName,
                                senderEmail = senderEmail,
                                receiverEmail = cleanEmail,
                                timestamp = timestamp,
                                status = status
                            )
                        )
                    }
                }
                checkDone()
            }
            .addOnFailureListener { checkDone() }

        // 3. Đồng bộ các lời mời mà tôi gửi đi (sender) và đã được đồng ý
        firestore.collection("friend_requests")
            .whereEqualTo("senderEmail", cleanEmail)
            .get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    val status = doc.getString("status") ?: "PENDING"
                    val receiverEmail = doc.getString("receiverEmail")?.trim()?.lowercase() ?: ""
                    val receiverName = doc.getString("receiverName") ?: receiverEmail.substringBefore("@")

                    if (status == "ACCEPTED" && receiverEmail.isNotBlank()) {
                        onContactFound(
                            com.vhn.connectx.model.User(
                                id = "u_${receiverEmail.hashCode()}",
                                displayName = receiverName,
                                email = receiverEmail,
                                isOnline = true
                            )
                        )
                    }
                }
                checkDone()
            }
            .addOnFailureListener { checkDone() }
    }

    /**
     * Respond to Friend Request (ACCEPTED or REJECTED) on Cloud Firestore.
     */
    fun respondFriendRequest(
        reqId: String,
        status: String,
        myDisplayName: String,
        onComplete: (Boolean) -> Unit
    ) {
        val updates = hashMapOf<String, Any>(
            "status" to status,
            "receiverName" to myDisplayName
        )
        firestore.collection("friend_requests")
            .document(reqId)
            .update(updates)
            .addOnSuccessListener {
                Log.i(TAG, "Friend request $reqId marked as $status on Firestore")
                if (status == "ACCEPTED") {
                    firestore.collection("friend_requests").document(reqId).get()
                        .addOnSuccessListener { doc ->
                            val sEmail = doc.getString("senderEmail")?.trim()?.lowercase() ?: ""
                            val sName = doc.getString("senderName") ?: sEmail.substringBefore("@")
                            val rEmail = doc.getString("receiverEmail")?.trim()?.lowercase() ?: ""
                            val rName = myDisplayName.ifBlank { rEmail.substringBefore("@") }
                            if (sEmail.isNotBlank() && rEmail.isNotBlank()) {
                                saveBilateralCloudContact(sEmail, sName, rEmail, rName)
                            }
                        }
                }
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to update friend request status: ${e.message}")
                onComplete(false)
            }
    }

    private fun saveBilateralCloudContact(user1Email: String, user1Name: String, user2Email: String, user2Name: String) {
        val contactForUser1 = hashMapOf(
            "email" to user2Email,
            "displayName" to user2Name,
            "id" to "u_${user2Email.hashCode()}",
            "updatedAt" to System.currentTimeMillis()
        )
        val contactForUser2 = hashMapOf(
            "email" to user1Email,
            "displayName" to user1Name,
            "id" to "u_${user1Email.hashCode()}",
            "updatedAt" to System.currentTimeMillis()
        )

        firestore.collection("users").document(user1Email).collection("contacts").document(user2Email)
            .set(contactForUser1, com.google.firebase.firestore.SetOptions.merge())
        firestore.collection("users").document(user2Email).collection("contacts").document(user1Email)
            .set(contactForUser2, com.google.firebase.firestore.SetOptions.merge())
    }

    /**
     * Send Message to Cloud Firestore for real-time delivery across any network (Wi-Fi, 4G, 5G).
     */
    fun sendMessage(convId: String, message: Message, onComplete: (Boolean) -> Unit) {
        val data = hashMapOf(
            "id" to message.id,
            "conversationId" to convId,
            "senderId" to message.senderId,
            "senderName" to message.senderName,
            "text" to message.text,
            "timestamp" to message.timestamp,
            "mediaUrl" to (message.mediaUrl ?: ""),
            "messageType" to message.messageType,
            "serverTime" to System.currentTimeMillis()
        )

        // 1. Update Conversation document summary
        val convSummary = hashMapOf(
            "id" to convId,
            "lastMessage" to if (message.messageType == "image") "📷 [Hình ảnh]" else message.text,
            "lastMessageSender" to message.senderName,
            "lastTimestamp" to message.timestamp,
            "lastServerTime" to System.currentTimeMillis()
        )
        firestore.collection("conversations")
            .document(convId)
            .set(convSummary, com.google.firebase.firestore.SetOptions.merge())

        // 2. Add message to subcollection
        firestore.collection("conversations")
            .document(convId)
            .collection("messages")
            .document(message.id)
            .set(data)
            .addOnSuccessListener {
                Log.i(TAG, "Message uploaded to Firestore: ${message.id}")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed uploading message to Firestore: ${e.message}")
                onComplete(false)
            }
    }

    /**
     * Listen in REAL-TIME for messages in a conversation.
     */
    fun listenMessages(
        convId: String,
        currentUserId: String,
        onUpdate: (List<Message>) -> Unit
    ): ListenerRegistration? {
        return try {
            firestore.collection("conversations")
                .document(convId)
                .collection("messages")
                .orderBy("serverTime", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Messages snapshot listener error: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        val list = mutableListOf<Message>()
                        for (doc in snapshot.documents) {
                            val id = doc.getString("id") ?: doc.id
                            val conversationId = doc.getString("conversationId") ?: convId
                            val senderId = doc.getString("senderId") ?: ""
                            val senderName = doc.getString("senderName") ?: ""
                            val text = doc.getString("text") ?: ""
                            val timestamp = doc.getString("timestamp") ?: ""
                            val mediaUrl = doc.getString("mediaUrl").takeIf { !it.isNullOrBlank() }
                            val messageType = doc.getString("messageType") ?: "text"

                            list.add(
                                Message(
                                    id = id,
                                    conversationId = conversationId,
                                    senderId = senderId,
                                    senderName = senderName,
                                    text = text,
                                    timestamp = timestamp,
                                    isMe = (senderId == currentUserId),
                                    mediaUrl = mediaUrl,
                                    messageType = messageType
                                )
                            )
                        }
                        onUpdate(list)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register messages listener", e)
            null
        }
    }

    /**
     * Listen in REAL-TIME for conversation summary update (to show in HomeActivity chats tab).
     */
    fun listenConversationSummary(
        convId: String,
        onUpdate: (lastMsg: String, lastTime: String, senderName: String) -> Unit
    ): ListenerRegistration? {
        return try {
            firestore.collection("conversations")
                .document(convId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    val lastMsg = snapshot.getString("lastMessage") ?: ""
                    val lastTime = snapshot.getString("lastTimestamp") ?: ""
                    val senderName = snapshot.getString("lastMessageSender") ?: ""
                    if (lastMsg.isNotBlank()) {
                        onUpdate(lastMsg, lastTime, senderName)
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Call Signaling: Khởi tạo cuộc gọi và gửi thông báo đổ chuông đến máy người nhận.
     */
    fun initiateCall(
        callId: String,
        callerEmail: String,
        callerName: String,
        calleeEmail: String,
        isVideo: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        val callData = hashMapOf(
            "id" to callId,
            "callerEmail" to callerEmail.trim().lowercase(),
            "callerName" to callerName,
            "calleeEmail" to calleeEmail.trim().lowercase(),
            "isVideo" to isVideo,
            "status" to "RINGING", // "RINGING", "ACCEPTED", "DECLINED", "ENDED"
            "createdAt" to System.currentTimeMillis()
        )

        firestore.collection("active_calls")
            .document(callId)
            .set(callData)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    /**
     * Lắng nghe cuộc gọi đến (Incoming Call) cho tài khoản hiện tại.
     */
    fun listenIncomingCall(
        myEmail: String,
        onNoRinging: (() -> Unit)? = null,
        onIncomingCall: (callId: String, callerName: String, callerEmail: String, isVideo: Boolean) -> Unit
    ): ListenerRegistration? {
        val cleanEmail = myEmail.trim().lowercase()
        if (cleanEmail.isBlank()) return null

        return try {
            firestore.collection("active_calls")
                .whereEqualTo("calleeEmail", cleanEmail)
                .whereEqualTo("status", "RINGING")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    if (snapshot.isEmpty) {
                        // Không còn cuộc gọi nào đang đổ chuông (đã nghe / từ chối / nhỡ / người gọi huỷ)
                        onNoRinging?.invoke()
                        return@addSnapshotListener
                    }
                    for (doc in snapshot.documents) {
                        val callId = doc.getString("id") ?: doc.id
                        val callerName = doc.getString("callerName") ?: "Bạn bè"
                        val callerEmail = doc.getString("callerEmail") ?: ""
                        val isVideo = doc.getBoolean("isVideo") ?: true
                        val createdAt = doc.getLong("createdAt") ?: 0L

                        // Cho phép chênh lệch thời gian máy ảo và máy thật lên tới 3 phút (hoặc cuộc gọi mới tạo)
                        val timeDiff = Math.abs(System.currentTimeMillis() - createdAt)
                        if (createdAt == 0L || timeDiff < 180000) {
                            onIncomingCall(callId, callerName, callerEmail, isVideo)
                            break
                        }
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Cập nhật trạng thái cuộc gọi (ACCEPTED, DECLINED, ENDED, MISSED).
     */
    fun updateCallStatus(callId: String, status: String, onComplete: ((Boolean) -> Unit)? = null) {
        if (callId.isBlank()) return
        firestore.collection("active_calls")
            .document(callId)
            .update("status", status)
            .addOnSuccessListener { onComplete?.invoke(true) }
            .addOnFailureListener { onComplete?.invoke(false) }
    }

    /**
     * Lắng nghe trạng thái cuộc gọi hiện tại (Đang đổ chuông, Đã nhấc máy, hoặc Đã gác máy).
     */
    fun listenCallStatus(
        callId: String,
        onStatusChange: (status: String) -> Unit
    ): ListenerRegistration? {
        if (callId.isBlank()) return null
        return try {
            firestore.collection("active_calls")
                .document(callId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    val status = snapshot.getString("status") ?: ""
                    if (status.isNotBlank()) {
                        onStatusChange(status)
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gửi Session Description (Offer hoặc Answer) lên Firestore cho cuộc gọi.
     */
    fun sendCallSdp(callId: String, type: String, sdpDescription: String) {
        if (callId.isBlank()) return
        val field = if (type.equals("offer", ignoreCase = true)) "offerSdp" else "answerSdp"
        firestore.collection("active_calls")
            .document(callId)
            .set(mapOf(field to sdpDescription), com.google.firebase.firestore.SetOptions.merge())
    }

    /**
     * Lắng nghe Remote Session Description (Offer hoặc Answer).
     */
    fun listenCallSdp(
        callId: String,
        targetType: String,
        onSdpReceived: (sdp: String) -> Unit
    ): ListenerRegistration? {
        if (callId.isBlank()) return null
        val field = if (targetType.equals("offer", ignoreCase = true)) "offerSdp" else "answerSdp"
        return try {
            firestore.collection("active_calls")
                .document(callId)
                .addSnapshotListener { snapshot, _ ->
                    val sdp = snapshot?.getString(field) ?: ""
                    if (sdp.isNotBlank()) {
                        onSdpReceived(sdp)
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gửi ICE Candidate đến subcollection của cuộc gọi.
     */
    fun sendIceCandidate(callId: String, senderRole: String, sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        if (callId.isBlank()) return
        val data = hashMapOf(
            "senderRole" to senderRole,
            "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex,
            "candidate" to candidate,
            "createdAt" to System.currentTimeMillis()
        )
        firestore.collection("active_calls")
            .document(callId)
            .collection("candidates")
            .add(data)
    }

    /**
     * Lắng nghe ICE Candidates từ đối phương.
     */
    fun listenRemoteIceCandidates(
        callId: String,
        targetRole: String,
        onCandidateReceived: (sdpMid: String, sdpMLineIndex: Int, candidate: String) -> Unit
    ): ListenerRegistration? {
        if (callId.isBlank()) return null
        return try {
            firestore.collection("active_calls")
                .document(callId)
                .collection("candidates")
                .whereEqualTo("senderRole", targetRole)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot == null) return@addSnapshotListener
                    for (doc in snapshot.documentChanges) {
                        if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val mid = doc.document.getString("sdpMid") ?: ""
                            val index = doc.document.getLong("sdpMLineIndex")?.toInt() ?: 0
                            val cand = doc.document.getString("candidate") ?: ""
                            if (cand.isNotBlank()) {
                                onCandidateReceived(mid, index, cand)
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            null
        }
    }
}

