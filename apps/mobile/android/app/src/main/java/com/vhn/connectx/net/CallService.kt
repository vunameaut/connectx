package com.vhn.connectx.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.ListenerRegistration
import com.vhn.connectx.CallActivity
import com.vhn.connectx.R
import com.vhn.connectx.model.SessionManager

/**
 * Background Call Signaling Service.
 * Chạy nền liên tục (như Zalo / Messenger / Telegram) để đón tín hiệu cuộc gọi
 * từ Cloud Firestore ngay cả khi app bị ẩn hoặc đóng hoàn toàn.
 * Khi có cuộc gọi:
 * - Tự động đổ chuông & rung.
 * - Hiện thông báo toàn màn hình (Full Screen Intent) chuẩn cuộc gọi đến.
 * - Bật sáng màn hình điện thoại và mở thẳng giao diện Nhấc máy / Từ chối.
 */
class CallService : Service() {

    companion object {
        const val TAG = "CallService"
        const val CHANNEL_ID_CALL_PERSISTENT = "connectx_call_persistent_service"
        const val CHANNEL_ID_INCOMING_CALL = "connectx_incoming_call_channel"
        const val NOTIFICATION_ID_PERSISTENT = 1001
        const val NOTIFICATION_ID_INCOMING = 1002

        fun start(context: Context) {
            val intent = Intent(context, CallService::class.java)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CallService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop CallService: ${e.message}")
            }
        }
    }

    private var callListener: ListenerRegistration? = null
    private var lastHandledCallId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        listenForIncomingCalls()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        listenForIncomingCalls()
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Kênh cho Persistent Service (độ ưu tiên thấp để không làm phiền)
            val persistentChannel = NotificationChannel(
                CHANNEL_ID_CALL_PERSISTENT,
                "ConnectX Trạng thái kết nối nền",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Duy trì kết nối nhận cuộc gọi và tin nhắn tức thì như Zalo"
                setShowBadge(false)
            }
            manager.createNotificationChannel(persistentChannel)

            // 2. Kênh cho Cuộc gọi đến (Độ ưu tiên cao nhất: Có chuông, Rung, Full Screen)
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val incomingChannel = NotificationChannel(
                CHANNEL_ID_INCOMING_CALL,
                "ConnectX Cuộc gọi đến",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo chuông cuộc gọi thoại & video ConnectX"
                setSound(ringtoneUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(incomingChannel)
        }
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_CALL_PERSISTENT)
            .setContentTitle("ConnectX đang hoạt động")
            .setContentText("Sẵn sàng nhận cuộc gọi & tin nhắn mới")
            .setSmallIcon(R.drawable.ic_calls)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID_PERSISTENT, notification)
    }

    private fun listenForIncomingCalls() {
        val sessionManager = SessionManager(this)
        val myEmail = sessionManager.currentUser.email
        if (myEmail.isBlank()) return

        callListener?.remove()
        callListener = FirebaseSyncManager.listenIncomingCall(myEmail) { callId, callerName, callerEmail, isVideo ->
            if (callId == lastHandledCallId) return@listenIncomingCall
            lastHandledCallId = callId

            Log.i(TAG, "Incoming call received in background: $callId from $callerName")
            showIncomingCallNotification(callId, callerName, callerEmail, isVideo)
        }
    }

    private fun showIncomingCallNotification(
        callId: String,
        callerName: String,
        callerEmail: String,
        isVideo: Boolean
    ) {
        val callIntent = Intent(this, CallActivity::class.java).apply {
            putExtra("EXTRA_CALL_ID", callId)
            putExtra("EXTRA_CONTACT_NAME", callerName)
            putExtra("EXTRA_CONTACT_EMAIL", callerEmail)
            putExtra("EXTRA_IS_VIDEO", isVideo)
            putExtra("EXTRA_IS_INCOMING", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            callId.hashCode(),
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_INCOMING_CALL)
            .setContentTitle(if (isVideo) "Cuộc gọi Video đến" else "Cuộc gọi Thoại đến")
            .setContentText("$callerName đang gọi cho bạn...")
            .setSmallIcon(R.drawable.ic_calls)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setSound(ringtoneUri)
            .setVibrate(longArrayOf(0, 1000, 1000, 1000, 1000))
            .setFullScreenIntent(pendingIntent, true) // Đánh thức màn hình và mở popup như Zalo
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_calls, "Nhấc máy", pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_INCOMING, notification)

        // Đồng thời khởi chạy trực tiếp Activity nếu hệ thống cho phép
        try {
            startActivity(callIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not directly start CallActivity from background: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callListener?.remove()
        callListener = null
    }
}
