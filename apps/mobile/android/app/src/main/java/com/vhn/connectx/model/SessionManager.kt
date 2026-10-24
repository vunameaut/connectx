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
    }

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

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
