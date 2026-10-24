package com.vhn.connectx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.connectx.screencapture.ScreenCaptureModule
import com.connectx.screencapture.ScreenCaptureService
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.vhn.connectx.data.AppDataStore
import com.vhn.connectx.model.CallHistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallActivity : AppCompatActivity() {

    private lateinit var tvCallAvatar: TextView
    private lateinit var tvCallContactName: TextView
    private lateinit var tvCallStatus: TextView
    private lateinit var tvCallTimer: TextView
    private lateinit var layoutScreenShareBanner: LinearLayout
    private lateinit var tvScreenShareBannerText: TextView

    private lateinit var btnToggleMic: FloatingActionButton
    private lateinit var tvMicLabel: TextView
    private lateinit var btnToggleVideo: FloatingActionButton
    private lateinit var tvVideoLabel: TextView
    private lateinit var btnToggleScreenShare: FloatingActionButton
    private lateinit var tvScreenShareLabel: TextView
    private lateinit var btnEndCall: FloatingActionButton

    private lateinit var screenCaptureModule: ScreenCaptureModule

    private var isMicMuted = false
    private var isVideoEnabled = true
    private var isScreenSharing = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        val contactName = intent.getStringExtra("EXTRA_CONTACT_NAME") ?: "Bạn bè"
        isVideoEnabled = intent.getBooleanExtra("EXTRA_IS_VIDEO", true)

        // Save real call into history
        val callItem = CallHistoryItem(
            id = "call_${System.currentTimeMillis()}",
            contactName = contactName,
            timestamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()),
            isVideo = isVideoEnabled,
            isIncoming = false
        )
        AppDataStore.getInstance(this).addCallHistory(callItem)

        initViews(contactName)
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

        // Start call timer
        timerHandler.postDelayed(timerRunnable, 1000)
    }

    private fun initViews(contactName: String) {
        tvCallAvatar = findViewById(R.id.tvCallAvatar)
        tvCallContactName = findViewById(R.id.tvCallContactName)
        tvCallStatus = findViewById(R.id.tvCallStatus)
        tvCallTimer = findViewById(R.id.tvCallTimer)
        layoutScreenShareBanner = findViewById(R.id.layoutScreenShareBanner)
        tvScreenShareBannerText = findViewById(R.id.tvScreenShareBannerText)

        btnToggleMic = findViewById(R.id.btnToggleMic)
        tvMicLabel = findViewById(R.id.tvMicLabel)
        btnToggleVideo = findViewById(R.id.btnToggleVideo)
        tvVideoLabel = findViewById(R.id.tvVideoLabel)
        btnToggleScreenShare = findViewById(R.id.btnToggleScreenShare)
        tvScreenShareLabel = findViewById(R.id.tvScreenShareLabel)
        btnEndCall = findViewById(R.id.btnEndCall)

        tvCallContactName.text = contactName
        tvCallAvatar.text = contactName.take(1).uppercase()
        tvCallStatus.text = if (isVideoEnabled) "Cuộc gọi Video WebRTC (TURN Connected)" else "Cuộc gọi Thoại WebRTC"
    }

    private fun setupCallControls() {
        btnToggleMic.setOnClickListener {
            isMicMuted = !isMicMuted
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
        if (isScreenSharing) {
            screenCaptureModule.stopCapture()
        }
        timerHandler.removeCallbacks(timerRunnable)
        Toast.makeText(this, "Cuộc gọi đã kết thúc", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        try {
            unregisterReceiver(captureReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        screenCaptureModule.cleanup()
    }
}
