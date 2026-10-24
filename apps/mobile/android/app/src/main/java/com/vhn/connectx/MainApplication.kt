package com.vhn.connectx

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.FirebaseApp
import com.vhn.connectx.data.AppDataStore

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Apply saved theme mode (Light / Dark / System default)
        try {
            val appDataStore = AppDataStore.getInstance(this)
            AppCompatDelegate.setDefaultNightMode(appDataStore.themeMode)
        } catch (e: Exception) {
            Log.w("MainApplication", "Theme initialization warning: ${e.message}")
        }
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
