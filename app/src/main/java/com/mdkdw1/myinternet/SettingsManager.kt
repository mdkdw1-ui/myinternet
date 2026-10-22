package com.mdkdw1.myinternet

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("myinternet", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", 1080)
        set(v) = prefs.edit().putInt("port", v).apply()

    var username: String
        get() = prefs.getString("username", "user") ?: "user"
        set(v) = prefs.edit().putString("username", v).apply()

    var password: String
        get() = prefs.getString("password", "changeme") ?: "changeme"
        set(v) = prefs.edit().putString("password", v).apply()
}
