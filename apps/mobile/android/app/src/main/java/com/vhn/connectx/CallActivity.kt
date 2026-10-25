package com.vhn.connectx

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.connectx.screencapture.ScreenCaptureModule
import com.connectx.screencapture.ScreenCaptureService
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.ListenerRegistration
import com.vhn.connectx.data.AppDataStore
import com.vhn.connectx.model.CallHistoryItem
import com.vhn.connectx.model.SessionManager
import com.vhn.connectx.net.FirebaseSyncManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallActivity : AppCompatActivity() {

    companion object {
        var isCallActive = false
    }

    private lateinit var tvCallAvatar: TextView
    private lateinit var tvCallContactName: TextView
    private lateinit var tvCallStatus: TextView
    private lateinit var tvCallTimer: TextView
    private lateinit var layoutScreenShareBanner: LinearLayout
    private lateinit var tvScreenShareBannerText: TextView

    private lateinit var callControlsBar: LinearLayout
    private lateinit var btnToggleMic: FloatingActionButton
    private lateinit var tvMicLabel: TextView
    private lateinit var btnToggleVideo: FloatingActionButton
    private lateinit var tvVideoLabel: TextView
    private lateinit var btnToggleScreenShare: FloatingActionButton
    private lateinit var tvScreenShareLabel: TextView
    private lateinit var btnEndCall: FloatingActionButton
    private lateinit var btnMinimizeCall: ImageButton
    private lateinit var layoutCallQualityBadge: View

    // WebRTC Video Surfaces
    private lateinit var remoteVideoView: org.webrtc.SurfaceViewRenderer
    private lateinit var localVideoView: org.webrtc.SurfaceViewRenderer
    private lateinit var cardLocalVideo: androidx.cardview.widget.CardView
    private lateinit var callHeaderLayout: LinearLayout

    // Incoming Call Controls
    private lateinit var layoutIncomingCallActions: LinearLayout
    private lateinit var btnAcceptCall: FloatingActionButton
    private lateinit var btnDeclineCall: FloatingActionButton

    private lateinit var screenCaptureModule: ScreenCaptureModule
    private lateinit var sessionManager: SessionManager
    private var webRtcManager: com.vhn.connectx.net.WebRtcManager? = null

    private var isMicMuted = false
    private var isVideoEnabled = true
    private var isScreenSharing = false
    private var isIncoming = false
    private var isCallAccepted = false

    private var callId: String = ""
    private var contactName: String = ""
    private var contactEmail: String = ""

    private var callStatusListener: ListenerRegistration? = null
    private var ringtone: Ringtone? = null

    private var callSeconds = 0
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            callSeconds++
            val minutes = callSeconds / 60
            val seconds = callSeconds % 60
            tvCallTimer.text = String.format("%02d:%02d", minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    private val connectingTimeoutHandler = Handler(Looper.getMainLooper())
    private val ringingTimeoutHandler = Handler(Looper.getMainLooper())
    private val CONNECTING_TIMEOUT_MS = 15000L // 15 seconds without ringing -> Unreachable
    private val RINGING_TIMEOUT_MS = 30000L    // 30 seconds ringing without answer -> Missed call
    private var isRingingReceived = false
    private var pendingScreenCaptureData: Intent? = null

    private fun dismissNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(1002)
            nm.cancelAll()
            androidx.core.app.NotificationManagerCompat.from(this).cancel(1002)
            androidx.core.app.NotificationManagerCompat.from(this).cancelAll()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val message = intent.getStringExtra("message") ?: ""

            when (action) {
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STARTED -> {
                    isScreenSharing = true
                    updateScreenShareUI(true, "ĐANG CHIA SẺ MÀN HÌNH")
                    val captureData = pendingScreenCaptureData
                    if (captureData != null) {
                        try {
                            webRtcManager?.startScreenShare(captureData)
                            Toast.makeText(this@CallActivity, "Đang phát trực tiếp màn hình qua WebRTC", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("CallActivity", "Error starting WebRTC screen share: ${e.message}", e)
                            Toast.makeText(this@CallActivity, "Lỗi kết nối màn hình: ${e.message}", Toast.LENGTH_LONG).show()
                            isScreenSharing = false
                            updateScreenShareUI(false, "")
                        }
                    }
                }
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STOPPED -> {
                    isScreenSharing = false
                    pendingScreenCaptureData = null
                    webRtcManager?.stopScreenShare()
                    updateScreenShareUI(false, "")
                    Toast.makeText(this@CallActivity, "Đã dừng chia sẻ màn hình", Toast.LENGTH_SHORT).show()
                }
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_ERROR -> {
                    isScreenSharing = false
                    pendingScreenCaptureData = null
                    webRtcManager?.stopScreenShare()
                    updateScreenShareUI(false, "")
                    Toast.makeText(this@CallActivity, "Lỗi chia sẻ màn hình: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (micGranted) {
            Log.i("CallActivity", "Microphone permission granted")
        } else {
            Toast.makeText(this, "Cần cấp quyền Micro để đàm thoại", Toast.LENGTH_SHORT).show()
        }
        if (isVideoEnabled && !cameraGranted) {
            Toast.makeText(this, "Cần cấp quyền Camera để bật hình ảnh", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestCallPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsNeeded.isNotEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dismissNotification()
        com.vhn.connectx.service.CallBackgroundService.stopRinging(this)
        Handler(Looper.getMainLooper()).postDelayed({ dismissNotification() }, 300)
    }

    override fun onResume() {
        super.onResume()
        dismissNotification()
        com.vhn.connectx.service.CallBackgroundService.stopRinging(this)
        Handler(Looper.getMainLooper()).postDelayed({ dismissNotification() }, 300)
        Handler(Looper.getMainLooper()).postDelayed({ dismissNotification() }, 1000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCallActive = true
        dismissNotification()
        com.vhn.connectx.service.CallBackgroundService.stopRinging(this)
        Handler(Looper.getMainLooper()).postDelayed({ dismissNotification() }, 300)

        // Wake screen and show over lockscreen like Zalo / WhatsApp
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request dismissing keyguard if locked
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager?.requestDismissKeyguard(this, null)
        }

        setContentView(R.layout.activity_call)

        sessionManager = SessionManager(this)
        contactName = intent.getStringExtra("EXTRA_CONTACT_NAME") ?: "Bạn bè"
        contactEmail = intent.getStringExtra("EXTRA_CONTACT_EMAIL") ?: ""
        isVideoEnabled = intent.getBooleanExtra("EXTRA_IS_VIDEO", true)
        isIncoming = intent.getBooleanExtra("EXTRA_IS_INCOMING", false)
        callId = intent.getStringExtra("EXTRA_CALL_ID") ?: "call_${System.currentTimeMillis()}"

        // Save into call history
        val callItem = CallHistoryItem(
            id = callId,
            contactName = contactName,
            timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
            isVideo = isVideoEnabled,
            isIncoming = isIncoming,
            status = if (isIncoming) "MISSED" else "UNREACHABLE",
            durationSeconds = 0,
            contactEmail = contactEmail
        )
        AppDataStore.getInstance(this).addCallHistory(callItem)

        initViews()
        setupCallControls()

        screenCaptureModule = ScreenCaptureModule(applicationContext)

        // Register screen capture broadcast listener
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STARTED)
            addAction(ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STOPPED)
            addAction(ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(captureReceiver, filter)
        }

        // Auto request microphone and camera permissions on 1st tap
        checkAndRequestCallPermissions()

        // Setup Call Flow
        if (isIncoming) {
            setupIncomingCallMode()
        } else {
            setupOutgoingCallMode()
        }
    }

    private fun initViews() {
        tvCallAvatar = findViewById(R.id.tvCallAvatar)
        tvCallContactName = findViewById(R.id.tvCallContactName)
        tvCallStatus = findViewById(R.id.tvCallStatus)
        tvCallTimer = findViewById(R.id.tvCallTimer)
        layoutScreenShareBanner = findViewById(R.id.layoutScreenShareBanner)
        tvScreenShareBannerText = findViewById(R.id.tvScreenShareBannerText)

        callControlsBar = findViewById(R.id.callControlsBar)
        btnToggleMic = findViewById(R.id.btnToggleMic)
        tvMicLabel = findViewById(R.id.tvMicLabel)
        btnToggleVideo = findViewById(R.id.btnToggleVideo)
        tvVideoLabel = findViewById(R.id.tvVideoLabel)
        btnToggleScreenShare = findViewById(R.id.btnToggleScreenShare)
        tvScreenShareLabel = findViewById(R.id.tvScreenShareLabel)
        btnEndCall = findViewById(R.id.btnEndCall)

        layoutIncomingCallActions = findViewById(R.id.layoutIncomingCallActions)
        btnAcceptCall = findViewById(R.id.btnAcceptCall)
        btnDeclineCall = findViewById(R.id.btnDeclineCall)
        remoteVideoView = findViewById(R.id.remoteVideoView)
        localVideoView = findViewById(R.id.localVideoView)
        cardLocalVideo = findViewById(R.id.cardLocalVideo)
        callHeaderLayout = findViewById(R.id.callHeaderLayout)

        // Essential: Set Z-Order Media Overlay for overlapping local preview
        try {
            localVideoView.setZOrderMediaOverlay(true)
            localVideoView.setEnableHardwareScaler(true)
            localVideoView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            remoteVideoView.setEnableHardwareScaler(true)
            remoteVideoView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        } catch (e: Exception) {
            Log.w("CallActivity", "Error setting video surface attributes: ${e.message}")
        }

        tvCallContactName.text = contactName
        tvCallAvatar.text = contactName.ifBlank { "U" }.take(1).uppercase()

        btnMinimizeCall = findViewById(R.id.btnMinimizeCall)
        layoutCallQualityBadge = findViewById(R.id.layoutCallQualityBadge)
        btnMinimizeCall.setOnClickListener {
            enterPipMode()
        }
        setupDraggableLocalVideo()
    }

    private fun setupOutgoingCallMode() {
        if (contactEmail.isBlank()) {
            Toast.makeText(this, "Không xác định được email người nhận. Hãy mở cuộc gọi từ danh bạ bạn bè.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        tvCallStatus.text = "Đang kết nối..."
        tvCallTimer.text = "Đang gửi tín hiệu..."
        callControlsBar.visibility = View.VISIBLE
        layoutIncomingCallActions.visibility = View.GONE

        // Start local camera preview immediately so caller can see themselves while waiting
        if (isVideoEnabled) {
            try {
                if (webRtcManager == null) {
                    webRtcManager = createWebRtcManager()
                }
                webRtcManager?.startCamera(localVideoView, isFront = true)
                cardLocalVideo.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.w("CallActivity", "Error starting camera preview during outgoing call: ${e.message}")
            }
        }

        // Send initiateCall to Firestore
        val myUser = sessionManager.currentUser
        if (contactEmail.isNotBlank()) {
            FirebaseSyncManager.initiateCall(
                callId = callId,
                callerEmail = myUser.email,
                callerName = myUser.displayName,
                calleeEmail = contactEmail,
                isVideo = isVideoEnabled
            ) {}
        }

        // 1. Timeout "không đổ chuông thì tắt": Chờ tối đa 15s nếu người nhận không có mạng / không đổ chuông
        connectingTimeoutHandler.removeCallbacksAndMessages(null)
        connectingTimeoutHandler.postDelayed({
            if (isCallAccepted || isRingingReceived || isFinishing || isDestroyed) return@postDelayed
            tvCallStatus.text = "Thuê bao hiện không thể liên lạc"
            FirebaseSyncManager.updateCallStatus(callId, "UNREACHABLE")
            AppDataStore.getInstance(this).updateCallHistoryStatus(callId, "UNREACHABLE")
            Toast.makeText(this, "Thuê bao hiện không có phản hồi tín hiệu (Không đổ chuông)", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
        }, CONNECTING_TIMEOUT_MS)

        // Listen for recipient response
        listenCallStatusUpdates()
    }

    private fun startRingingTimeout() {
        ringingTimeoutHandler.removeCallbacksAndMessages(null)
        ringingTimeoutHandler.postDelayed({
            if (isCallAccepted || isFinishing || isDestroyed) return@postDelayed
            tvCallStatus.text = "Người nhận không trả lời"
            FirebaseSyncManager.updateCallStatus(callId, "MISSED")
            AppDataStore.getInstance(this).updateCallHistoryStatus(callId, "MISSED")
            Toast.makeText(this, "Không có phản hồi từ đối phương (Hết thời gian chờ)", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
        }, RINGING_TIMEOUT_MS)
    }

    private fun setupIncomingCallMode() {
        tvCallStatus.text = if (isVideoEnabled) "Cuộc gọi video đến..." else "Cuộc gọi thoại đến..."
        tvCallTimer.text = "Đang đổ chuông..."
        callControlsBar.visibility = View.GONE
        layoutIncomingCallActions.visibility = View.VISIBLE

        // Báo cho phía người gọi biết máy mình đã nhận tín hiệu và đang đổ chuông
        FirebaseSyncManager.markCallRinging(callId)

        // Play standard incoming ringtone
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Timeout "đổ chuông bao lâu không nghe thì tắt": 30s đổ chuông không nghe thì tự tắt
        ringingTimeoutHandler.removeCallbacksAndMessages(null)
        ringingTimeoutHandler.postDelayed({
            if (isCallAccepted || isFinishing || isDestroyed) return@postDelayed
            ringtone?.stop()
            dismissNotification()
            tvCallStatus.text = "Cuộc gọi nhỡ"
            FirebaseSyncManager.updateCallStatus(callId, "MISSED")
            AppDataStore.getInstance(this).updateCallHistoryStatus(callId, "MISSED")
            Toast.makeText(this, "Cuộc gọi nhỡ từ $contactName", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
        }, RINGING_TIMEOUT_MS)

        btnAcceptCall.setOnClickListener {
            ringtone?.stop()
            dismissNotification()
            connectingTimeoutHandler.removeCallbacksAndMessages(null)
            ringingTimeoutHandler.removeCallbacksAndMessages(null)
            isCallAccepted = true
            FirebaseSyncManager.updateCallStatus(callId, "ACCEPTED")
            onCallConnected()
        }

        btnDeclineCall.setOnClickListener {
            ringtone?.stop()
            dismissNotification()
            connectingTimeoutHandler.removeCallbacksAndMessages(null)
            ringingTimeoutHandler.removeCallbacksAndMessages(null)
            FirebaseSyncManager.updateCallStatus(callId, "DECLINED")
            AppDataStore.getInstance(this).updateCallHistoryStatus(callId, "DECLINED")
            Toast.makeText(this, "Đã từ chối cuộc gọi", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Tự động nhận cuộc gọi nếu người dùng đã bấm nút "Trả lời" ngay trên thông báo banner
        if (intent.getBooleanExtra("EXTRA_AUTO_ACCEPT", false)) {
            btnAcceptCall.performClick()
        }

        // Listen if caller hangs up before answering
        listenCallStatusUpdates()
    }

    private fun listenCallStatusUpdates() {
        callStatusListener?.remove()
        callStatusListener = FirebaseSyncManager.listenCallStatus(callId) { status, isRinging ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                // Nếu cuộc gọi đã kết nối thành công, tuyệt đối KHÔNG chấp nhận các trạng thái trễ gây ngắt cuộc gọi
                if (isCallAccepted && (status == "MISSED" || status == "UNREACHABLE" || status == "RINGING" || status == "DECLINED")) {
                    Log.w("CallActivity", "Ignoring late status update '$status' because call is already active")
                    return@runOnUiThread
                }

                // Khi đối phương bắt đầu đổ chuông thực tế
                if (isRinging && !isRingingReceived && !isCallAccepted && !isIncoming) {
                    isRingingReceived = true
                    connectingTimeoutHandler.removeCallbacksAndMessages(null)
                    tvCallStatus.text = "Đang đổ chuông..."
                    tvCallTimer.text = "Đang đổ chuông..."
                    startRingingTimeout()
                }

                when (status) {
                    "ACCEPTED" -> {
                        connectingTimeoutHandler.removeCallbacksAndMessages(null)
                        ringingTimeoutHandler.removeCallbacksAndMessages(null)
                        AppDataStore.getInstance(this).updateCallHistoryStatus(callId, "CONNECTED")
                        if (!isCallAccepted) {
                            onCallConnected()
                        }
                    }
                    "DECLINED" -> {
                        ringtone?.stop()
                        dismissNotification()
                        connectingTimeoutHandler.removeCallbacksAndMessages(null)
                        ringingTimeoutHandler.removeCallbacksAndMessages(null)
                        AppDataStore.getInstance(this).updateCallHistoryStatus(callId, "DECLINED")
                        tvCallStatus.text = "Đối phương bận máy"
                        Toast.makeText(this, "Đối phương đã từ chối cuộc gọi", Toast.LENGTH_SHORT).show()
                        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
                    }
                    "MISSED" -> {
                        ringtone?.stop()
                        dismissNotification()
                        connectingTimeoutHandler.removeCallbacksAndMessages(null)
                        ringingTimeoutHandler.removeCallbacksAndMessages(null)
                        AppDataStore.getInstance(this).updateCallHistoryStatus(callId, "MISSED")
                        tvCallStatus.text = "Người nhận không trả lời"
                        Toast.makeText(this, "Không có phản hồi từ đối phương", Toast.LENGTH_SHORT).show()
                        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
                    }
                    "UNREACHABLE" -> {
                        ringtone?.stop()
                        dismissNotification()
                        connectingTimeoutHandler.removeCallbacksAndMessages(null)
                        ringingTimeoutHandler.removeCallbacksAndMessages(null)
                        AppDataStore.getInstance(this).updateCallHistoryStatus(callId, "UNREACHABLE")
                        tvCallStatus.text = "Thuê bao không thể liên lạc"
                        Toast.makeText(this, "Thuê bao hiện không thể liên lạc được (Không có kết nối)", Toast.LENGTH_SHORT).show()
                        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1800)
                    }
                    "CANCELLED", "ENDED" -> {
                        ringtone?.stop()
                        dismissNotification()
                        connectingTimeoutHandler.removeCallbacksAndMessages(null)
                        ringingTimeoutHandler.removeCallbacksAndMessages(null)
                        val finalStatus = if (isCallAccepted) "CONNECTED" else if (status == "CANCELLED") "CANCELLED" else "ENDED"
                        AppDataStore.getInstance(this).updateCallHistoryStatus(callId, finalStatus, callSeconds)
                        tvCallStatus.text = if (status == "CANCELLED") "Người gọi đã huỷ" else "Cuộc gọi đã kết thúc"
                        timerHandler.removeCallbacks(timerRunnable)
                        Toast.makeText(this, if (status == "CANCELLED") "Cuộc gọi đã bị huỷ" else "Cuộc gọi đã kết thúc", Toast.LENGTH_SHORT).show()
                        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
                    }
                }
            }
        }
    }

    private fun onCallConnected() {
        isCallAccepted = true
        AppDataStore.getInstance(this).updateCallHistoryStatus(callId, "CONNECTED")
        ringtone?.stop()
        dismissNotification()
        connectingTimeoutHandler.removeCallbacksAndMessages(null)
        ringingTimeoutHandler.removeCallbacksAndMessages(null)
        callControlsBar.visibility = View.VISIBLE
        layoutIncomingCallActions.visibility = View.GONE
        tvCallStatus.text = if (isVideoEnabled) "Đang thiết lập kết nối hình ảnh..." else "Đang thiết lập kết nối thoại..."

        // Start call timer when answered (nhấc máy thì đếm giờ)
        callSeconds = 0
        tvCallTimer.text = "00:00"
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.postDelayed(timerRunnable, 1000)

        // Initialize WebRTC Engine
        setupWebRtcMedia()
    }

    private fun createWebRtcManager(): com.vhn.connectx.net.WebRtcManager {
        return com.vhn.connectx.net.WebRtcManager(
            context = applicationContext,
            callId = callId,
            isCaller = !isIncoming
        ).apply {
            onRemoteVideoTrackReady = {
                runOnUiThread {
                    remoteVideoView.visibility = View.VISIBLE
                    callHeaderLayout.visibility = View.GONE
                    tvCallStatus.text = "Đang truyền hình ảnh"
                }
            }
            onIceConnected = {
                runOnUiThread { tvCallStatus.text = "Đã kết nối" }
            }
            onIceFailed = { reason ->
                runOnUiThread {
                    tvCallStatus.text = reason
                    Toast.makeText(this@CallActivity, reason, Toast.LENGTH_LONG).show()
                }
            }
            onCallDisconnected = {
                runOnUiThread {
                    tvCallStatus.text = "Tín hiệu mạng không ổn định..."
                }
            }
        }
    }

    private fun setupWebRtcMedia() {
        try {
            if (webRtcManager == null) {
                webRtcManager = createWebRtcManager()
            }

            // If Video Call is enabled, start local camera preview if not already active
            if (isVideoEnabled) {
                try {
                    webRtcManager?.startCamera(localVideoView, isFront = true)
                    cardLocalVideo.visibility = View.VISIBLE
                } catch (e: Exception) {
                    Log.e("CallActivity", "Error starting camera: ${e.message}")
                }
            }

            // Start PeerConnection & Audio/Video streaming
            webRtcManager?.startPeerConnection(remoteVideoView)
        } catch (e: Exception) {
            Log.e("CallActivity", "Fatal error in setupWebRtcMedia: ${e.message}", e)
            Toast.makeText(this, "Lỗi khởi tạo truyền thông WebRTC: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupCallControls() {
        btnToggleMic.setOnClickListener {
            isMicMuted = !isMicMuted
            webRtcManager?.toggleMicrophone(!isMicMuted)
            if (isMicMuted) {
                tvMicLabel.text = "Tắt mic"
                btnToggleMic.backgroundTintList = getColorStateList(R.color.call_red)
                Toast.makeText(this, "Đã tắt micro", Toast.LENGTH_SHORT).show()
            } else {
                tvMicLabel.text = "Mic"
                btnToggleMic.backgroundTintList = getColorStateList(R.color.bg_card_secondary)
                Toast.makeText(this, "Đã bật micro", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleVideo.setOnClickListener {
            isVideoEnabled = !isVideoEnabled
            webRtcManager?.toggleCamera(isVideoEnabled)
            cardLocalVideo.visibility = if (isVideoEnabled) View.VISIBLE else View.GONE
            if (isVideoEnabled) {
                tvVideoLabel.text = "Camera"
                btnToggleVideo.backgroundTintList = getColorStateList(R.color.bg_card_secondary)
                Toast.makeText(this, "Đã bật camera", Toast.LENGTH_SHORT).show()
            } else {
                tvVideoLabel.text = "Tắt camera"
                btnToggleVideo.backgroundTintList = getColorStateList(R.color.call_red)
                Toast.makeText(this, "Đã tắt camera", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleScreenShare.setOnClickListener {
            if (!isScreenSharing) {
                tvCallStatus.text = "Yêu cầu quyền chia sẻ màn hình..."
                screenCaptureModule.startCaptureIntent(this) { success, error ->
                    if (!success) {
                        tvCallStatus.text = "Từ chối chia sẻ ($error)"
                        Toast.makeText(this, "Bạn đã từ chối chia sẻ màn hình: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                isScreenSharing = false
                pendingScreenCaptureData = null
                webRtcManager?.stopScreenShare()
                screenCaptureModule.stopCapture()
                updateScreenShareUI(false, "")
            }
        }

        btnEndCall.setOnClickListener {
            endCall()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        screenCaptureModule.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCaptureModule.REQUEST_CODE_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            // Lưu intent data, đợi Foreground Service kích hoạt xong (BROADCAST_SCREEN_CAPTURE_STARTED) mới start WebRTC ScreenCapturer
            pendingScreenCaptureData = data
        }
    }

    private fun updateScreenShareUI(isSharing: Boolean, bannerText: String) {
        if (isSharing) {
            layoutScreenShareBanner.visibility = View.VISIBLE
            tvScreenShareBannerText.text = bannerText
            tvScreenShareLabel.text = "Dừng chia sẻ"
            tvScreenShareLabel.setTextColor(Color.parseColor("#EF4444"))
            btnToggleScreenShare.backgroundTintList = getColorStateList(R.color.call_red)
            tvCallStatus.text = "Đang truyền tải màn hình qua WebRTC"
            cardLocalVideo.visibility = View.GONE
        } else {
            layoutScreenShareBanner.visibility = View.GONE
            tvScreenShareLabel.text = "Chia sẻ"
            tvScreenShareLabel.setTextColor(Color.parseColor("#38BDF8"))
            btnToggleScreenShare.backgroundTintList = getColorStateList(R.color.colorPrimary)
            tvCallStatus.text = if (isVideoEnabled) "Cuộc gọi Video WebRTC (TURN Connected)" else "Cuộc gọi Thoại WebRTC"
            cardLocalVideo.visibility = if (isVideoEnabled) View.VISIBLE else View.GONE
        }
    }

    private fun endCall() {
        ringtone?.stop()
        dismissNotification()
        connectingTimeoutHandler.removeCallbacksAndMessages(null)
        ringingTimeoutHandler.removeCallbacksAndMessages(null)
        if (isScreenSharing) {
            webRtcManager?.stopScreenShare()
            screenCaptureModule.stopCapture()
        }
        timerHandler.removeCallbacks(timerRunnable)
        val finalStatus = if (isCallAccepted) "CONNECTED" else "CANCELLED"
        AppDataStore.getInstance(this).updateCallHistoryStatus(callId, finalStatus, callSeconds)
        FirebaseSyncManager.updateCallStatus(callId, "ENDED")
        webRtcManager?.cleanup()
        webRtcManager = null
        Toast.makeText(this, "Cuộc gọi đã kết thúc", Toast.LENGTH_SHORT).show()
        finish()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableLocalVideo() {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var isDragging = false
        val density = resources.displayMetrics.density

        cardLocalVideo.setOnTouchListener { view, event ->
            val parentView = view.parent as? View ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = view.x
                    startY = view.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > 10 * density) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val minX = 12f * density
                        val maxX = (parentView.width - view.width - 12f * density).coerceAtLeast(minX)
                        val minY = 48f * density
                        val maxY = (parentView.height - view.height - 120f * density).coerceAtLeast(minY)

                        view.x = (startX + dx).coerceIn(minX, maxX)
                        view.y = (startY + dy).coerceIn(minY, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        val minX = 12f * density
                        val maxX = (parentView.width - view.width - 12f * density).coerceAtLeast(minX)
                        val middleX = parentView.width / 2f
                        val targetX = if (view.x + view.width / 2f < middleX) minX else maxX

                        view.animate()
                            .x(targetX)
                            .setDuration(250)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    } else {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Tỷ lệ 9:16 chuẩn cho cửa sổ PiP cuộc gọi di động
                val aspectRatio = Rational(9, 16)
                val pipBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                enterPictureInPictureMode(pipBuilder.build())
            } catch (e: Exception) {
                Log.e("CallActivity", "Error entering PiP mode: ${e.message}")
                moveTaskToBack(true)
            }
        } else {
            moveTaskToBack(true)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isCallAccepted && !isFinishing && !isDestroyed) {
            enterPipMode()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isCallAccepted) {
            enterPipMode()
        } else {
            endCall()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // Khi ở chế độ thu nhỏ PiP: ẩn dock điều khiển và nút bấm để ưu tiên màn hình video
            callControlsBar.visibility = View.GONE
            btnMinimizeCall.visibility = View.GONE
            layoutCallQualityBadge.visibility = View.GONE
            cardLocalVideo.visibility = View.GONE
            layoutScreenShareBanner.visibility = View.GONE

            if (remoteVideoView.visibility != View.VISIBLE) {
                callHeaderLayout.visibility = View.VISIBLE
                tvCallContactName.visibility = View.VISIBLE
                tvCallTimer.visibility = View.VISIBLE
                tvCallStatus.visibility = View.GONE
            }
        } else {
            // Khi người dùng bấm vào PiP phóng to lại toàn màn hình: khôi phục giao diện
            btnMinimizeCall.visibility = View.VISIBLE
            layoutCallQualityBadge.visibility = View.VISIBLE
            if (isCallAccepted) {
                callControlsBar.visibility = View.VISIBLE
                if (isVideoEnabled && !isScreenSharing) {
                    cardLocalVideo.visibility = View.VISIBLE
                }
                if (isScreenSharing) {
                    layoutScreenShareBanner.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun finish() {
        isCallActive = false
        super.finish()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            isCallActive = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCallActive = false
        ringtone?.stop()
        com.vhn.connectx.service.CallBackgroundService.stopRinging(this)
        dismissNotification()
        connectingTimeoutHandler.removeCallbacksAndMessages(null)
        ringingTimeoutHandler.removeCallbacksAndMessages(null)
        callStatusListener?.remove()
        timerHandler.removeCallbacks(timerRunnable)
        try {
            unregisterReceiver(captureReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        try {
            localVideoView.release()
            remoteVideoView.release()
        } catch (e: Exception) {
            Log.w("CallActivity", "Error releasing SurfaceViewRenderers: ${e.message}")
        }
        webRtcManager?.cleanup()
        webRtcManager = null
        screenCaptureModule.cleanup()
    }
}
