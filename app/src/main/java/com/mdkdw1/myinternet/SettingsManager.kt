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
        get() = prefs.getString("username", "myinternet") ?: "myinternet"
        set(v) = prefs.edit().putString("username", v).apply()
    var password: String
        get() = prefs.getString("password", "myinternet") ?: "myinternet"
        set(v) = prefs.edit().putString("password", v).apply()

    var srvHost: String
        get() = prefs.getString("srvHost", "") ?: ""
        set(v) = prefs.edit().putString("srvHost", v).apply()
    var srvPort: Int
        get() = prefs.getInt("srvPort", 1080)
        set(v) = prefs.edit().putInt("srvPort", v).apply()
    var srvUser: String
        get() = prefs.getString("srvUser", "myinternet") ?: "myinternet"
        set(v) = prefs.edit().putString("srvUser", v).apply()
    var srvPass: String
        get() = prefs.getString("srvPass", "myinternet") ?: "myinternet"
        set(v) = prefs.edit().putString("srvPass", v).apply()
}
