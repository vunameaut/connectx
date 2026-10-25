package com.vhn.connectx.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration
import com.vhn.connectx.CallActivity
import com.vhn.connectx.HomeActivity
import com.vhn.connectx.R
import com.vhn.connectx.model.SessionManager
import com.vhn.connectx.net.FirebaseSyncManager

class CallBackgroundService : Service() {

    companion object {
        private const val TAG = "CallBackgroundService"
        const val CHANNEL_SERVICE_ID = "connectx_call_service_channel"
        const val CHANNEL_CALL_ID = "connectx_incoming_calls_v3"
        const val NOTIFICATION_SERVICE_ID = 1001
        const val NOTIFICATION_CALL_ID = 1002

        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_STOP_RINGING = "ACTION_STOP_RINGING"
        const val ACTION_DECLINE_CALL = "ACTION_DECLINE_CALL"

        @Volatile
        var isServiceRunning = false
            private set

        fun start(context: Context) {
            try {
                val intent = Intent(context, CallBackgroundService::class.java).apply {
                    action = ACTION_START_SERVICE
                }
                try {
                    context.startService(intent)
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, intent)
                    } else {
                        throw e
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CallBackgroundService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, CallBackgroundService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop CallBackgroundService: ${e.message}", e)
            }
        }

        fun stopRinging(context: Context) {
            try {
                val intent = Intent(context, CallBackgroundService::class.java).apply {
                    action = ACTION_STOP_RINGING
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop ringing: ${e.message}", e)
            }
        }
    }

    private var incomingCallListener: ListenerRegistration? = null
    private var lastHandledCallId = ""
    private var currentActiveCallId: String? = null
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceWakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        acquireServiceWakeLock()
        initVibrator()
        createNotificationChannels()
    }

    private fun acquireServiceWakeLock() {
        try {
            if (serviceWakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                serviceWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ConnectX:CallBackgroundServiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (serviceWakeLock?.isHeld == false) {
                serviceWakeLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire service wake lock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SERVICE

        when (action) {
            ACTION_STOP_SERVICE -> {
                stopRingtoneAndVibration()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_RINGING -> {
                stopRingtoneAndVibration()
                dismissCallNotification()
                lastHandledCallId = ""
                currentActiveCallId = null
                return START_STICKY
            }
            ACTION_DECLINE_CALL -> {
                val callId = intent?.getStringExtra("EXTRA_CALL_ID") ?: currentActiveCallId
                if (!callId.isNullOrBlank()) {
                    FirebaseSyncManager.updateCallStatus(callId, "DECLINED")
                }
                stopRingtoneAndVibration()
                dismissCallNotification()
                lastHandledCallId = ""
                currentActiveCallId = null
                return START_STICKY
            }
            else -> {
                startAsForeground()
                startListeningForIncomingCalls()

                // Check if invoked directly with an incoming call event from FCM
                val pushCallId = intent?.getStringExtra("EXTRA_CALL_ID")
                if (!pushCallId.isNullOrBlank() && pushCallId != lastHandledCallId) {
                    val callerName = intent.getStringExtra("EXTRA_CONTACT_NAME") ?: "Bạn bè"
                    val callerEmail = intent.getStringExtra("EXTRA_CONTACT_EMAIL") ?: ""
                    val isVideo = intent.getBooleanExtra("EXTRA_IS_VIDEO", true)
                    lastHandledCallId = pushCallId
                    currentActiveCallId = pushCallId
                    triggerIncomingCall(pushCallId, callerName, callerEmail, isVideo)
                }

                return START_STICKY
            }
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Service Background Keep-Alive Channel (Silent & Minimized)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "ConnectX Dịch vụ Cuộc gọi",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Giữ kết nối nhận cuộc gọi ConnectX"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(serviceChannel)

            // 2. Incoming Call High-Priority Channel (Loud Ringtone & Vibration)
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val callChannel = NotificationChannel(
                CHANNEL_CALL_ID,
                "ConnectX Cuộc gọi đến",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo chuông cuộc gọi thoại & video ConnectX toàn màn hình"
                setSound(ringtoneUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            manager.createNotificationChannel(callChannel)
        }
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.cancel(NOTIFICATION_SERVICE_ID)
        } catch (e: Exception) {}

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_calls)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setNotificationSilent()
            .setContentIntent(pendingIntent)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgsType = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    fgsType = fgsType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                fgsType = fgsType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_SERVICE_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_SERVICE_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground error: ${e.message}")
        }

        // Tắt bỏ hoàn toàn thông báo "Đang sẵn sàng nhận cuộc gọi đến", không để lưu lại trên thanh trạng thái
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            manager.cancel(NOTIFICATION_SERVICE_ID)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground error: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        startListeningForIncomingCalls()
    }

    private fun startListeningForIncomingCalls() {
        val sessionManager = SessionManager(this)
        val myEmail = sessionManager.currentUser.email
        if (myEmail.isBlank()) {
            Log.w(TAG, "Not logged in, skipping incoming call listener")
            return
        }

        incomingCallListener?.remove()
        incomingCallListener = FirebaseSyncManager.listenIncomingCall(
            myEmail = myEmail,
            onNoRinging = {
                stopRingtoneAndVibration()
                dismissCallNotification()
                lastHandledCallId = ""
                currentActiveCallId = null
            }
        ) { callId, callerName, callerEmail, isVideo ->
            if (CallActivity.isCallActive) {
                Log.i(TAG, "CallActivity already active, ignoring incoming call $callId")
                return@listenIncomingCall
            }

            if (callId == lastHandledCallId) return@listenIncomingCall
            lastHandledCallId = callId
            currentActiveCallId = callId

            triggerIncomingCall(callId, callerName, callerEmail, isVideo)
        }
    }

    fun triggerIncomingCall(callId: String, callerName: String, callerEmail: String, isVideo: Boolean) {
        acquireWakeLock()
        startRingtoneAndVibration()
        // Báo ngay lập tức cho người gọi biết máy mình đã nhận và đang đổ chuông (hủy 15s timeout của người gọi)
        FirebaseSyncManager.markCallRinging(callId)
        showFullScreenCallNotification(callId, callerName, callerEmail, isVideo)
        launchCallActivity(callId, callerName, callerEmail, isVideo)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
                wakeLock = powerManager.newWakeLock(flags, "ConnectX:IncomingCallWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(35000L) // Giữ sáng màn hình tối đa 35 giây
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire warning: ${e.message}")
        }
    }

    private fun startRingtoneAndVibration() {
        try {
            stopRingtoneAndVibration()

            // 1. Play continuous ringtone
            val ringtoneUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    streamType = AudioManager.STREAM_RING
                }
                play()
            }

            // 2. Play continuous vibration pattern
            val pattern = longArrayOf(0, 1000, 1000, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = repeat from index 0
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startRingtoneAndVibration warning: ${e.message}")
        }
    }

    fun stopRingtoneAndVibration() {
        try {
            ringtone?.stop()
            ringtone = null
        } catch (e: Exception) {
            // Ignore
        }
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun showFullScreenCallNotification(
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
            putExtra("EXTRA_AUTO_ACCEPT", false)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            callId.hashCode(),
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent khi bấm nút "Trả lời" trên banner -> Tự động nhấc máy ngay lập tức
        val answerIntent = Intent(this, CallActivity::class.java).apply {
            putExtra("EXTRA_CALL_ID", callId)
            putExtra("EXTRA_CONTACT_NAME", callerName)
            putExtra("EXTRA_CONTACT_EMAIL", callerEmail)
            putExtra("EXTRA_IS_VIDEO", isVideo)
            putExtra("EXTRA_IS_INCOMING", true)
            putExtra("EXTRA_AUTO_ACCEPT", true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        val answerPendingIntent = PendingIntent.getActivity(
            this,
            callId.hashCode() + 2,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, CallBackgroundService::class.java).apply {
            action = ACTION_DECLINE_CALL
            putExtra("EXTRA_CALL_ID", callId)
        }
        val declinePendingIntent = PendingIntent.getService(
            this,
            callId.hashCode() + 1,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isVideo) "Cuộc gọi Video đến" else "Cuộc gọi Thoại đến"
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val callerPerson = androidx.core.app.Person.Builder()
            .setName(callerName)
            .setKey(callerEmail)
            .setImportant(true)
            .build()

        val callStyle = NotificationCompat.CallStyle.forIncomingCall(
            callerPerson,
            declinePendingIntent,
            answerPendingIntent
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_CALL_ID)
            .setStyle(callStyle)
            .setContentTitle(title)
            .setContentText("$callerName đang gọi cho bạn...")
            .setSmallIcon(if (isVideo) R.drawable.ic_videocall else R.drawable.ic_calls)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(ringtoneUri)
            .setVibrate(longArrayOf(0, 1000, 1000, 1000, 1000))
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_call_end, "Từ chối", declinePendingIntent)
            .addAction(if (isVideo) R.drawable.ic_videocall else R.drawable.ic_calls, "Trả lời", answerPendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_CALL_ID, notification)
    }

    private fun launchCallActivity(
        callId: String,
        callerName: String,
        callerEmail: String,
        isVideo: Boolean
    ) {
        try {
            val callIntent = Intent(this, CallActivity::class.java).apply {
                putExtra("EXTRA_CALL_ID", callId)
                putExtra("EXTRA_CONTACT_NAME", callerName)
                putExtra("EXTRA_CONTACT_EMAIL", callerEmail)
                putExtra("EXTRA_IS_VIDEO", isVideo)
                putExtra("EXTRA_IS_INCOMING", true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }

            val pi = PendingIntent.getActivity(
                this,
                callId.hashCode() + 10,
                callIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = android.app.ActivityOptions.makeBasic()
                try {
                    val method = android.app.ActivityOptions::class.java.getMethod(
                        "setPendingIntentBackgroundActivityStartMode",
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(options, 1) // 1 = MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                } catch (e: Exception) {
                    // Ignore
                }
                try {
                    pi.send(this, 0, null, null, null, null, options.toBundle())
                } catch (e: Exception) {
                    startActivity(callIntent, options.toBundle())
                }
            } else {
                try {
                    pi.send()
                } catch (e: Exception) {
                    startActivity(callIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct launch CallActivity failed: ${e.message}")
        }
    }

    private fun dismissCallNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_CALL_ID)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopRingtoneAndVibration()
        dismissCallNotification()
        incomingCallListener?.remove()
        incomingCallListener = null
        try {
            if (serviceWakeLock?.isHeld == true) {
                serviceWakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
