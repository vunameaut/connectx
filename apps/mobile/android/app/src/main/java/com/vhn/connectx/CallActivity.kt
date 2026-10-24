package com.vhn.connectx

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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

    private val ringingTimeoutHandler = Handler(Looper.getMainLooper())
    private val CALL_TIMEOUT_MS = 35000L // 35 seconds ringing timeout

    private fun dismissNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(1002)
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
                    Toast.makeText(this@CallActivity, "Đang chia sẻ màn hình", Toast.LENGTH_SHORT).show()
                }
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STOPPED -> {
                    isScreenSharing = false
                    updateScreenShareUI(false, "")
                    Toast.makeText(this@CallActivity, "Đã dừng chia sẻ màn hình", Toast.LENGTH_SHORT).show()
                }
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_ERROR -> {
                    isScreenSharing = false
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCallActive = true
        dismissNotification()

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
            isIncoming = isIncoming
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
            remoteVideoView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        } catch (e: Exception) {
            Log.w("CallActivity", "Error setting video surface attributes: ${e.message}")
        }

        tvCallContactName.text = contactName
        tvCallAvatar.text = contactName.ifBlank { "U" }.take(1).uppercase()
    }

    private fun setupOutgoingCallMode() {
        if (contactEmail.isBlank()) {
            Toast.makeText(this, "Không xác định được email người nhận. Hãy mở cuộc gọi từ danh bạ bạn bè.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        tvCallStatus.text = "Đang gọi..."
        tvCallTimer.text = "Đang kết nối..."
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

        // Set 35s timeout if callee doesn't answer
        ringingTimeoutHandler.removeCallbacksAndMessages(null)
        ringingTimeoutHandler.postDelayed({
            if (!isCallAccepted && !isFinishing && !isDestroyed) {
                tvCallStatus.text = "Người nhận không trả lời"
                FirebaseSyncManager.updateCallStatus(callId, "MISSED")
                Toast.makeText(this, "Không có phản hồi từ đối phương (Hết thời gian chờ)", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 2000)
            }
        }, CALL_TIMEOUT_MS)

        // Listen for recipient response
        listenCallStatusUpdates()
    }

    private fun setupIncomingCallMode() {
        tvCallStatus.text = if (isVideoEnabled) "Cuộc gọi video đến..." else "Cuộc gọi thoại đến..."
        tvCallTimer.text = "Đang đổ chuông..."
        callControlsBar.visibility = View.GONE
        layoutIncomingCallActions.visibility = View.VISIBLE

        // Play standard incoming ringtone
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Set 35s ringing timeout for callee
        ringingTimeoutHandler.removeCallbacksAndMessages(null)
        ringingTimeoutHandler.postDelayed({
            if (!isCallAccepted && !isFinishing && !isDestroyed) {
                ringtone?.stop()
                dismissNotification()
                tvCallStatus.text = "Cuộc gọi nhỡ"
                Toast.makeText(this, "Cuộc gọi nhỡ từ $contactName", Toast.LENGTH_SHORT).show()
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
            }
        }, CALL_TIMEOUT_MS)

        btnAcceptCall.setOnClickListener {
            ringtone?.stop()
            dismissNotification()
            ringingTimeoutHandler.removeCallbacksAndMessages(null)
            isCallAccepted = true
            FirebaseSyncManager.updateCallStatus(callId, "ACCEPTED")
            onCallConnected()
        }

        btnDeclineCall.setOnClickListener {
            ringtone?.stop()
            dismissNotification()
            ringingTimeoutHandler.removeCallbacksAndMessages(null)
            FirebaseSyncManager.updateCallStatus(callId, "DECLINED")
            Toast.makeText(this, "Đã từ chối cuộc gọi", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Listen if caller hangs up before answering
        listenCallStatusUpdates()
    }

    private fun listenCallStatusUpdates() {
        callStatusListener?.remove()
        callStatusListener = FirebaseSyncManager.listenCallStatus(callId) { status ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (status) {
                    "ACCEPTED" -> {
                        if (!isCallAccepted) {
                            ringingTimeoutHandler.removeCallbacksAndMessages(null)
                            onCallConnected()
                        }
                    }
                    "DECLINED" -> {
                        ringtone?.stop()
                        dismissNotification()
                        ringingTimeoutHandler.removeCallbacksAndMessages(null)
                        tvCallStatus.text = "Đối phương bận máy"
                        Toast.makeText(this, "Đối phương đã từ chối cuộc gọi", Toast.LENGTH_SHORT).show()
                        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
                    }
                    "MISSED" -> {
                        ringtone?.stop()
                        dismissNotification()
                        ringingTimeoutHandler.removeCallbacksAndMessages(null)
                        tvCallStatus.text = "Người nhận không trả lời"
                        Toast.makeText(this, "Không có phản hồi từ đối phương", Toast.LENGTH_SHORT).show()
                        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
                    }
                    "ENDED" -> {
                        ringtone?.stop()
                        dismissNotification()
                        ringingTimeoutHandler.removeCallbacksAndMessages(null)
                        tvCallStatus.text = "Cuộc gọi đã kết thúc"
                        timerHandler.removeCallbacks(timerRunnable)
                        Toast.makeText(this, "Cuộc gọi đã kết thúc", Toast.LENGTH_SHORT).show()
                        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
                    }
                }
            }
        }
    }

    private fun onCallConnected() {
        isCallAccepted = true
        ringtone?.stop()
        dismissNotification()
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
                webRtcManager?.stopScreenShare()
                screenCaptureModule.stopCapture()
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
            try {
                webRtcManager?.startScreenShare(resultCode, data)
            } catch (e: Exception) {
                Log.e("CallActivity", "Error starting WebRTC screen share: ${e.message}")
            }
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
        } else {
            layoutScreenShareBanner.visibility = View.GONE
            tvScreenShareLabel.text = "Chia sẻ"
            tvScreenShareLabel.setTextColor(Color.parseColor("#38BDF8"))
            btnToggleScreenShare.backgroundTintList = getColorStateList(R.color.colorPrimary)
            tvCallStatus.text = if (isVideoEnabled) "Cuộc gọi Video WebRTC (TURN Connected)" else "Cuộc gọi Thoại WebRTC"
        }
    }

    private fun endCall() {
        ringtone?.stop()
        dismissNotification()
        ringingTimeoutHandler.removeCallbacksAndMessages(null)
        if (isScreenSharing) {
            webRtcManager?.stopScreenShare()
            screenCaptureModule.stopCapture()
        }
        timerHandler.removeCallbacks(timerRunnable)
        FirebaseSyncManager.updateCallStatus(callId, "ENDED")
        webRtcManager?.cleanup()
        webRtcManager = null
        Toast.makeText(this, "Cuộc gọi đã kết thúc", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        isCallActive = false
        ringtone?.stop()
        dismissNotification()
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
