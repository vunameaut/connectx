package com.vhn.connectx

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.connectx.screencapture.ScreenCaptureModule
import com.connectx.screencapture.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var screenCaptureModule: ScreenCaptureModule

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val message = intent.getStringExtra("message") ?: ""

            when (action) {
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STARTED -> {
                    updateCaptureState(isCapturing = true, "ACTIVE (Broadcasting)")
                }
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STOPPED -> {
                    updateCaptureState(isCapturing = false, "IDLE ($message)")
                }
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_ERROR -> {
                    updateCaptureState(isCapturing = false, "ERROR: $message")
                    Toast.makeText(this@MainActivity, "Capture error: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvScreenShareStatus)
        btnStart = findViewById(R.id.btnStartScreenShare)
        btnStop = findViewById(R.id.btnStopScreenShare)

        screenCaptureModule = ScreenCaptureModule(applicationContext)

        btnStart.setOnClickListener {
            tvStatus.text = "Screen Sharing: REQUESTING CONSENT..."
            tvStatus.setTextColor(Color.parseColor("#F59E0B"))

            screenCaptureModule.startCaptureIntent(this) { success, error ->
                if (!success) {
                    tvStatus.text = "Screen Sharing: DENIED ($error)"
                    tvStatus.setTextColor(Color.parseColor("#DC2626"))
                    Toast.makeText(this, "Permission denied: $error", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnStop.setOnClickListener {
            screenCaptureModule.stopCapture()
        }

        // Register broadcast receiver
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
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        screenCaptureModule.onActivityResult(requestCode, resultCode, data)
    }

    private fun updateCaptureState(isCapturing: Boolean, statusText: String) {
        tvStatus.text = "Screen Sharing: $statusText"
        if (isCapturing) {
            tvStatus.setTextColor(Color.parseColor("#EF4444"))
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            tvStatus.setTextColor(Color.parseColor("#22C55E"))
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(captureReceiver)
        screenCaptureModule.cleanup()
    }
}
