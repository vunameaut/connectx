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
     * Listen in REAL-TIME for incoming friend requests for the given email.
     */
    fun listenIncomingFriendRequests(
        myEmail: String,
        onUpdate: (List<FriendRequest>) -> Unit
    ): ListenerRegistration? {
        val cleanEmail = myEmail.trim().lowercase()
        if (cleanEmail.isBlank()) return null

        return try {
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
                        onUpdate(list)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register friend requests snapshot listener", e)
            null
        }
    }

    /**
     * Respond to Friend Request (ACCEPTED or REJECTED) on Cloud Firestore.
     */
    fun respondFriendRequest(reqId: String, status: String, onComplete: (Boolean) -> Unit) {
        firestore.collection("friend_requests")
            .document(reqId)
            .update("status", status)
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
}
