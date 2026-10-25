package com.vhn.connectx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vhn.connectx.model.SessionManager

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        Log.i("BootCompletedReceiver", "Received boot event: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val sessionManager = SessionManager(context)
            if (sessionManager.isLoggedIn && sessionManager.currentUser.email.isNotBlank()) {
                Log.i("BootCompletedReceiver", "User logged in, auto-starting CallBackgroundService")
                CallBackgroundService.start(context)
            }
        }
    }
}
