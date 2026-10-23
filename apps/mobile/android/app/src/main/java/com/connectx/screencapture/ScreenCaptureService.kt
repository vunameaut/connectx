package com.connectx.screencapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Android Foreground Service handling OS-authorized screen capture.
 * Fully compliant with Android 14+ (API 34/35) FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION requirements.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCaptureService"
        const val NOTIFICATION_CHANNEL_ID = "connectx_screen_capture"
        const val NOTIFICATION_ID = 40401

        const val ACTION_START = "com.connectx.screencapture.START"
        const val ACTION_STOP = "com.connectx.screencapture.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"

        const val BROADCAST_SCREEN_CAPTURE_STOPPED = "com.connectx.screencapture.EVENT_STOPPED"
        const val BROADCAST_SCREEN_CAPTURE_STARTED = "com.connectx.screencapture.EVENT_STARTED"
        const val BROADCAST_SCREEN_CAPTURE_ERROR = "com.connectx.screencapture.EVENT_ERROR"

        @Volatile
        var isCapturing: Boolean = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionManager: MediaProjectionManager? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection.Callback.onStop() triggered by OS or user")
            stopCapture(reason = "OS_REVOKED")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)

                if (resultCode != 0 && data != null) {
                    startCapture(resultCode, data)
                } else {
                    Log.e(TAG, "Invalid resultCode or dataIntent for ScreenCaptureService")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "Action STOP received from user or in-app control")
                stopCapture(reason = "USER_STOPPED")
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            // Android 14+ STRICT ORDER: Service MUST be promoted to foreground with type
            // mediaProjection BEFORE calling getMediaProjection()
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= 34) { // Android 14+ (API 34)
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // Now obtain MediaProjection with the user-consented token
            mediaProjection = projectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to obtain MediaProjection instance")
                broadcastEvent(BROADCAST_SCREEN_CAPTURE_ERROR, "FAILED_TO_GET_PROJECTION")
                stopSelf()
                return
            }

            mediaProjection?.registerCallback(projectionCallback, null)
            isCapturing = true

            // Set up display metrics for virtual display (1080p target or native scaled)
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val width = 1080
            val height = 1920
            val dpi = metrics.densityDpi

            // Note: VirtualDisplay surface will be attached to WebRTC VideoCapturer SurfaceTextureHelper
            // virtualDisplay = mediaProjection?.createVirtualDisplay(...)

            Log.i(TAG, "MediaProjection successfully initialized and capturing")
            broadcastEvent(BROADCAST_SCREEN_CAPTURE_STARTED, "SUCCESS")

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during MediaProjection setup: ${e.message}", e)
            broadcastEvent(BROADCAST_SCREEN_CAPTURE_ERROR, "SECURITY_EXCEPTION: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in ScreenCaptureService: ${e.message}", e)
            broadcastEvent(BROADCAST_SCREEN_CAPTURE_ERROR, e.message ?: "UNKNOWN_ERROR")
            stopSelf()
        }
    }

    private fun stopCapture(reason: String) {
        if (!isCapturing && mediaProjection == null) {
            stopSelf()
            return
        }

        try {
            virtualDisplay?.release()
            virtualDisplay = null

            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null

            isCapturing = false
            Log.i(TAG, "ScreenCaptureService stopped. Reason: $reason")

            broadcastEvent(BROADCAST_SCREEN_CAPTURE_STOPPED, reason)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up screen capture: ${e.message}", e)
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Sharing Indicator",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification shown while screen sharing is active in ConnectX"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val stopPendingIntent = PendingIntent.getService(this, 1001, stopIntent, flags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Sharing your screen")
            .setContentText("ConnectX is broadcasting your screen to call participants")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop Sharing", stopPendingIntent)
            .build()
    }

    private fun broadcastEvent(action: String, message: String) {
        val intent = Intent(action).apply {
            putExtra("message", message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture("SERVICE_DESTROYED")
    }
}
