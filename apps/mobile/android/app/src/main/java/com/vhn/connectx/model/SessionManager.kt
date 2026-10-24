package com.vhn.connectx.model

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("connectx_session", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_FIREBASE_TOKEN = "firebase_token"
        private const val KEY_SUPABASE_TOKEN = "supabase_token"
        private const val KEY_SUPABASE_PROFILE_ID = "supabase_profile_id"
        private const val KEY_API_BASE_URL = "api_base_url"
    }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    var firebaseToken: String?
        get() = prefs.getString(KEY_FIREBASE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FIREBASE_TOKEN, value).apply()

    var supabaseToken: String?
        get() = prefs.getString(KEY_SUPABASE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_SUPABASE_TOKEN, value).apply()

    var supabaseProfileId: String?
        get() = prefs.getString(KEY_SUPABASE_PROFILE_ID, null)
        set(value) = prefs.edit().putString(KEY_SUPABASE_PROFILE_ID, value).apply()

    var apiBaseUrl: String
        get() = prefs.getString(KEY_API_BASE_URL, "http://10.0.2.2:4000") ?: "http://10.0.2.2:4000"
        set(value) = prefs.edit().putString(KEY_API_BASE_URL, value).apply()

    var currentUser: User
        get() {
            val id = prefs.getString(KEY_USER_ID, "user_demo_01") ?: "user_demo_01"
            val name = prefs.getString(KEY_DISPLAY_NAME, "Bạn (ConnectX)") ?: "Bạn (ConnectX)"
            val email = prefs.getString(KEY_EMAIL, "user@connectx.app") ?: "user@connectx.app"
            return User(id = id, displayName = name, email = email)
        }
        set(user) {
            prefs.edit()
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putString(KEY_USER_ID, user.id)
                .putString(KEY_DISPLAY_NAME, user.displayName)
                .putString(KEY_EMAIL, user.email)
                .apply()
        }

    fun logout() {
        prefs.edit().clear().apply()
    }
}
