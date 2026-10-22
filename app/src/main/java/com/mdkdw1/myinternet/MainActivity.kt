package com.mdkdw1.myinternet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var settings: SettingsManager
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var ipText: TextView
    private lateinit var toggleBtn: Button
    private val handler = Handler(Looper.getMainLooper())

    private val versionName: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.000" }
        catch (_: Exception) { "0.000" }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val srv = Socks5Service.server
            if (srv != null && Socks5Service.isRunning) {
                statsText.text = "활성 ${srv.activeConnections.get()}  ·  총 ${srv.totalConnections.get()}\n" +
                        "전송 ${fmt(srv.bytesTransferred.get())}"
            } else statsText.text = ""
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        buildUI()
        handler.post(refresh)
    }

    private fun buildUI() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply { text = "🌐 MyInternet"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = "v$versionName"; textSize = 13f
            setTextColor(0xFF888888.toInt()); setPadding(0, 0, 0, 32)
        })

        root.addView(title("서버 주소 (클라이언트에서 접속할 IP)"))
        ipText = TextView(this).apply {
            text = localIps().joinToString("\n").ifEmpty { "IP 없음 (핫스팟 확인)" }
            typeface = Typeface.MONOSPACE; textSize = 16f; setPadding(0, 8, 0, 8)
        }
        root.addView(ipText)
        root.addView(Button(this).apply {
            text = "주소 복사"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ip", ipText.text))
                toast("복사됨")
            }
        })

        root.addView(title("서버 설정"))
        val port = EditText(this).apply {
            hint = "포트"; setText(settings.port.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        val user = EditText(this).apply { hint = "사용자명"; setText(settings.username) }
        val pass = EditText(this).apply {
            hint = "비밀번호"; setText(settings.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(port, user, pass).forEach { root.addView(it) }

        toggleBtn = Button(this).apply {
            text = if (Socks5Service.isRunning) "서버 중지" else "서버 시작"
            setOnClickListener {
                if (Socks5Service.isRunning) stopServer()
                else {
                    settings.port = port.text.toString().toIntOrNull() ?: 1080
                    settings.username = user.text.toString().ifBlank { "user" }
                    settings.password = pass.text.toString().ifBlank { "changeme" }
                    startServer()
                }
            }
        }
        root.addView(toggleBtn)

        statusText = TextView(this).apply {
            text = if (Socks5Service.isRunning) "🟢 실행 중" else "⚪ 중지됨"
            textSize = 16f; setPadding(0, 16, 0, 8)
        }
        root.addView(statusText)
        statsText = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }
        root.addView(statsText)

        root.addView(title("클라이언트 연결 방법"))
        root.addView(TextView(this).apply {
            text = """
                1) 클라이언트를 이 폰의 핫스팟에 연결
                2) Wi-Fi 설정 → 프록시 → 수동
                3) 호스트: 위 IP  /  포트: ${settings.port}
                4) 인증: 사용자명·비밀번호 입력
                
                · PC Chrome: --proxy-server="socks5://IP:PORT"
                · Termux: proxychains 에 socks5 IP PORT USER PASS
            """.trimIndent()
            textSize = 13f; setPadding(0, 8, 0, 16)
        })

        root.addView(title("업데이트"))
        root.addView(Button(this).apply {
            text = "업데이트 확인"
            setOnClickListener { checkUpdate() }
        })

        setContentView(scroll)
    }

    private fun startServer() {
        val i = Intent(this, Socks5Service::class.java).apply { action = Socks5Service.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toggleBtn.text = "서버 중지"; statusText.text = "🟢 실행 중"; toast("서버 시작")
    }

    private fun stopServer() {
        startService(Intent(this, Socks5Service::class.java).apply { action = Socks5Service.ACTION_STOP })
        toggleBtn.text = "서버 시작"; statusText.text = "⚪ 중지됨"; toast("서버 중지")
    }

    private fun checkUpdate() {
        toast("확인 중...")
        UpdateChecker().check(versionName) { info ->
            if (info != null) {
                AlertDialog.Builder(this)
                    .setTitle("업데이트 발견")
                    .setMessage("v${info.version} (현재 v$versionName)\n\n다운로드 페이지로 이동할까요?")
                    .setPositiveButton("다운로드") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                    }
                    .setNegativeButton("나중에", null).show()
            } else toast("최신 버전입니다 (v$versionName)")
        }
    }

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 17f
        setPadding(0, 32, 0, 8)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun localIps(): List<String> {
        val r = mutableListOf<String>()
        try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                if (iface.isUp && !iface.isLoopback) iface.inetAddresses.toList().forEach { a ->
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) a.hostAddress?.let { r.add(it) }
                }
            }
        } catch (_: Exception) {}
        return r
    }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
        else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
    }

    override fun onDestroy() {
        super.onDestroy(); handler.removeCallbacks(refresh)
    }
}
