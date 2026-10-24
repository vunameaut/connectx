package com.vhn.connectx

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
        } catch (e: Exception) {
            Log.e("MainApplication", "FirebaseApp init warning: ${e.message}")
        }

        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("ConnectX_Crash", "FATAL EXCEPTION in thread ${thread.name}: ${throwable.message}", throwable)
            val prefs = getSharedPreferences("connectx_crash", MODE_PRIVATE)
            prefs.edit().putString("last_crash", "${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTraceToString()}").apply()
            originalHandler?.uncaughtException(thread, throwable)
        }
    }
}
