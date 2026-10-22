package com.mdkdw1.myinternet

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private var server: Socks5Server? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        val title = TextView(this).apply {
            text = "MyInternet v" + packageManager.getPackageInfo(packageName, 0).versionName
            textSize = 20f
        }
        val portInput = EditText(this).apply { hint = "Port"; setText("1080"); inputType = InputType.TYPE_CLASS_NUMBER }
        val userInput = EditText(this).apply { hint = "Username"; setText("user") }
        val passInput = EditText(this).apply {
            hint = "Password"; setText("changeme")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val info = TextView(this).apply { text = "대기 중" }
        val toggle = Button(this).apply {
            text = "서버 시작"
            setOnClickListener {
                if (running) {
                    server?.stop(); server = null; running = false
                    text = "서버 시작"; info.text = "중지됨"
                } else {
                    val port = portInput.text.toString().toIntOrNull() ?: 1080
                    val u = userInput.text.toString()
                    val p = passInput.text.toString()
                    if (u.isBlank() || p.isBlank()) { info.text = "사용자/비밀번호 필요"; return@setOnClickListener }
                    server = Socks5Server(port, u, p).also { it.start() }
                    running = true
                    text = "서버 중지"
                    info.text = "IP: " + localIp() + ":" + port
                }
            }
        }
        listOf(title, portInput, userInput, passInput, toggle, info).forEach { layout.addView(it) }
        setContentView(layout)
    }

    private fun localIp(): String {
        try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                iface.inetAddresses.toList().forEach { a ->
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress ?: "?"
                }
            }
        } catch (_: Exception) {}
        return "?"
    }

    override fun onDestroy() { super.onDestroy(); server?.stop() }
}
