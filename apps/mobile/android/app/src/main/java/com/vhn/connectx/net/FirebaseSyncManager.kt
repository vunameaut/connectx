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

        // 1. Incoming pending requests (where I am receiver)
        val incomingListener = try {
            firestore.collection("friend_requests")
                .whereEqualTo("receiverEmail", cleanEmail)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Friend requests listen failed: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        val list = mutableListOf<FriendRequest>()
                        for (doc in snapshot.documents) {
                            val id = doc.getString("id") ?: doc.id
                            val senderId = doc.getString("senderId") ?: ""
                            val senderName = doc.getString("senderName") ?: ""
                            val senderEmail = doc.getString("senderEmail") ?: ""
                            val receiverEmail = doc.getString("receiverEmail") ?: ""
                            val timestamp = doc.getString("timestamp") ?: ""
                            val status = doc.getString("status") ?: "PENDING"

                            if (status == "PENDING") {
                                list.add(
                                    FriendRequest(
                                        id = id,
                                        senderId = senderId,
                                        senderName = senderName,
                                        senderEmail = senderEmail,
                                        receiverEmail = receiverEmail,
                                        timestamp = timestamp,
                                        status = status
                                    )
                                )
                            }
                        }
                        onIncomingUpdate(list)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register incoming friend requests listener", e)
            null
        }

        // 2. Outgoing requests that were ACCEPTED by the recipient (where I was sender)
        val acceptedListener = try {
            firestore.collection("friend_requests")
                .whereEqualTo("senderEmail", cleanEmail)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Accepted requests listen failed: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        val list = mutableListOf<FriendRequest>()
                        for (doc in snapshot.documents) {
                            val id = doc.getString("id") ?: doc.id
                            val senderId = doc.getString("senderId") ?: ""
                            val senderName = doc.getString("senderName") ?: ""
                            val senderEmail = doc.getString("senderEmail") ?: ""
                            val receiverEmail = doc.getString("receiverEmail") ?: ""
                            val receiverName = doc.getString("receiverName") ?: receiverEmail.substringBefore("@")
                            val timestamp = doc.getString("timestamp") ?: ""
                            val status = doc.getString("status") ?: "PENDING"

                            if (status == "ACCEPTED") {
                                list.add(
                                    FriendRequest(
                                        id = id,
                                        senderId = senderId,
                                        senderName = receiverName, // Use receiver's name for sender's contact list
                                        senderEmail = senderEmail,
                                        receiverEmail = receiverEmail,
                                        timestamp = timestamp,
                                        status = status
                                    )
                                )
                            }
                        }
                        onAcceptedUpdate(list)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register accepted requests listener", e)
            null
        }

        return Pair(incomingListener, acceptedListener)
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
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to update friend request status: ${e.message}")
                onComplete(false)
            }
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
                    for (doc in snapshot.documents) {
                        val callId = doc.getString("id") ?: doc.id
                        val callerName = doc.getString("callerName") ?: "Bạn bè"
                        val callerEmail = doc.getString("callerEmail") ?: ""
                        val isVideo = doc.getBoolean("isVideo") ?: true
                        val createdAt = doc.getLong("createdAt") ?: 0L

                        // Chỉ chấp nhận cuộc gọi trong vòng 60 giây gần nhất
                        if (System.currentTimeMillis() - createdAt < 60000) {
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
     * Cập nhật trạng thái cuộc gọi (ACCEPTED, DECLINED, ENDED).
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
            .update(field, sdpDescription)
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

