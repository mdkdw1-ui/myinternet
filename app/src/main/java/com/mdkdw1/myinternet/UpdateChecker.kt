package com.mdkdw1.myinternet

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker {
    data class ReleaseInfo(val version: String, val url: String)

    fun check(currentVersion: String, callback: (ReleaseInfo?) -> Unit) {
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/mdkdw1-ui/myinternet/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode != 200) { post(callback, null); return@Thread }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                var apk = ""
                for (k in 0 until assets.length()) {
                    val a = assets.getJSONObject(k)
                    if (a.getString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); break }
                }
                post(callback, if (isNewer(tag, currentVersion)) ReleaseInfo(tag, apk) else null)
            } catch (_: Exception) { post(callback, null) }
        }.start()
    }

    private fun post(cb: (ReleaseInfo?) -> Unit, r: ReleaseInfo?) {
        Handler(Looper.getMainLooper()).post { cb(r) }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val a = remote.split(".").mapNotNull { it.toIntOrNull() }
        val b = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x > y) return true
            if (x < y) return false
        }
        return false
    }
}
