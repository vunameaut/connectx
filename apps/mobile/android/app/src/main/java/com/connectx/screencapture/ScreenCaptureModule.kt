package com.connectx.screencapture

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log

/**
 * ScreenCapture Native Controller & Bridge.
 * Manages Intent request flow for OS consent and links with ScreenCaptureService.
 */
class ScreenCaptureModule(private val context: Context) {

    companion object {
        const val TAG = "ScreenCaptureModule"
        const val REQUEST_CODE_MEDIA_PROJECTION = 10042
    }

    interface Listener {
        fun onStarted()
        fun onStopped(reason: String)
        fun onError(code: String, message: String)
    }

    private var listener: Listener? = null
    private var pendingRequestCallback: ((Boolean, String?) -> Unit)? = null

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val message = intent.getStringExtra("message") ?: ""

            when (action) {
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STARTED -> {
                    listener?.onStarted()
                }
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STOPPED -> {
                    listener?.onStopped(message)
                }
                ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_ERROR -> {
                    listener?.onError("CAPTURE_ERROR", message)
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STARTED)
            addAction(ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_STOPPED)
            addAction(ScreenCaptureService.BROADCAST_SCREEN_CAPTURE_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(eventReceiver, filter)
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Initiates OS Screen Capture Consent Flow.
     * Launches the system confirmation dialog.
     */
    fun startCaptureIntent(activity: Activity, callback: (Boolean, String?) -> Unit) {
        val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager == null) {
            callback(false, "MEDIA_PROJECTION_NOT_SUPPORTED")
            return
        }

        this.pendingRequestCallback = callback
        val captureIntent = projectionManager.createScreenCaptureIntent()
        activity.startActivityForResult(captureIntent, REQUEST_CODE_MEDIA_PROJECTION)
    }

    /**
     * Handles Activity Result from OS consent dialog.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE_MEDIA_PROJECTION) {
            return false
        }

        val cb = pendingRequestCallback
        pendingRequestCallback = null

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "User rejected or dismissed OS screen capture permission")
            cb?.invoke(false, "USER_CANCELLED")
            listener?.onError("USER_CANCELLED", "User denied screen capture permission")
            return true
        }

        // Start Foreground Service with obtained result token
        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA_INTENT, data)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            cb?.invoke(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ScreenCaptureService: ${e.message}", e)
            cb?.invoke(false, "START_SERVICE_FAILED: ${e.message}")
            listener?.onError("FOREGROUND_SERVICE_FAILED", e.message ?: "Failed to start service")
        }

        return true
    }

    /**
     * Stop active screen capture.
     */
    fun stopCapture() {
        val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    fun isCapturing(): Boolean {
        return ScreenCaptureService.isCapturing
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(eventReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
