package com.vhn.connectx.service

import android.content.Intent
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vhn.connectx.model.SessionManager

class ConnectXFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "ConnectX_FCM"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM Token received: $token")
        val sessionManager = SessionManager(this)
        sessionManager.firebaseToken = token

        val myEmail = sessionManager.currentUser.email
        if (myEmail.isNotBlank()) {
            try {
                FirebaseFirestore.getInstance().collection("users")
                    .document(myEmail.trim().lowercase())
                    .set(mapOf("fcmToken" to token, "updatedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        Log.i(TAG, "FCM token synced to Firestore for $myEmail")
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Sync FCM token failed: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "FCM message received: data=${remoteMessage.data}")

        val data = remoteMessage.data
        val type = data["type"] ?: ""
        val callId = data["callId"] ?: data["id"] ?: ""

        if (type == "CALL_INCOMING" || callId.isNotBlank()) {
            val callerName = data["callerName"] ?: "Bạn bè"
            val callerEmail = data["callerEmail"] ?: ""
            val isVideo = (data["isVideo"] ?: "true").toBoolean()

            // Wake up background call service to handle full-screen ringing
            val serviceIntent = Intent(this, CallBackgroundService::class.java).apply {
                action = CallBackgroundService.ACTION_START_SERVICE
                putExtra("EXTRA_CALL_ID", callId)
                putExtra("EXTRA_CONTACT_NAME", callerName)
                putExtra("EXTRA_CONTACT_EMAIL", callerEmail)
                putExtra("EXTRA_IS_VIDEO", isVideo)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
}
