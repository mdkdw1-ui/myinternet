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
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var settings: SettingsManager
    private lateinit var serverPanel: LinearLayout
    private lateinit var clientPanel: LinearLayout
    private var mode = "server"

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var ipText: TextView
    private lateinit var toggleBtn: Button

    private lateinit var vpnStatusText: TextView
    private lateinit var vpnBtn: Button
    private lateinit var vpnInfoText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val VPN_REQ = 200

    private val versionName: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.000" }
        catch (_: Exception) { "0.000" }
    }

    private val refresh = object : Runnable {
        override fun run() {
            // 서버 상태
            val srv = Socks5Service.server
            if (srv != null && Socks5Service.isRunning) {
                statsText.text = "활성 ${srv.activeConnections.get()}  ·  총 ${srv.totalConnections.get()}\n" +
                        "전송 ${fmt(srv.bytesTransferred.get())}"
            } else statsText.text = ""
            // VPN 상태
            if (MyVpnService.isRunning) {
                vpnStatusText.text = "🟢 VPN 연결됨"
                vpnBtn.text = "VPN 해제"
                vpnInfoText.text = "서버: ${settings.srvHost}:${settings.srvPort}"
            } else {
                vpnStatusText.text = "⚪ VPN 중지"
                vpnBtn.text = "VPN 연결"
                vpnInfoText.text = MyVpnService.lastError?.let { "오류: $it" } ?: ""
            }
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
            setTextColor(0xFF888888.toInt()); setPadding(0, 0, 0, 20)
        })

        // 모드 탭
        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        val rbServer = RadioButton(this).apply { text = "서버"; id = View.generateViewId(); isChecked = true }
        val rbClient = RadioButton(this).apply { text = "클라이언트"; id = View.generateViewId() }
        modeGroup.addView(rbServer); modeGroup.addView(rbClient)
        modeGroup.setOnCheckedChangeListener { _, id ->
            mode = if (id == rbServer.id) "server" else "client"
            serverPanel.visibility = if (mode == "server") View.VISIBLE else View.GONE
            clientPanel.visibility = if (mode == "client") View.VISIBLE else View.GONE
        }
        root.addView(modeGroup)

        serverPanel = buildServerPanel()
        clientPanel = buildClientPanel().apply { visibility = View.GONE }
        root.addView(serverPanel)
        root.addView(clientPanel)

        root.addView(title("업데이트"))
        root.addView(Button(this).apply {
            text = "업데이트 확인"
            setOnClickListener { checkUpdate() }
        })

        setContentView(scroll)
    }

    private fun buildServerPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(title("서버 주소 (클라이언트에서 접속할 IP)"))
        ipText = TextView(this).apply {
            text = localIps().joinToString("\n").ifEmpty { "IP 없음 (핫스팟 확인)" }
            typeface = Typeface.MONOSPACE; textSize = 16f; setPadding(0, 8, 0, 8)
        }
        p.addView(ipText)
        p.addView(Button(this).apply {
            text = "주소 복사"
            setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ip", ipText.text))
                toast("복사됨")
            }
        })
        p.addView(title("서버 설정"))
        val port = EditText(this).apply {
            hint = "포트"; setText(settings.port.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        val user = EditText(this).apply { hint = "사용자명"; setText(settings.username) }
        val pass = EditText(this).apply {
            hint = "비밀번호"; setText(settings.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(port, user, pass).forEach { p.addView(it) }
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
        p.addView(toggleBtn)
        statusText = TextView(this).apply {
            text = if (Socks5Service.isRunning) "🟢 실행 중" else "⚪ 중지됨"
            textSize = 16f; setPadding(0, 16, 0, 8)
        }
        p.addView(statusText)
        statsText = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }
        p.addView(statsText)
        return p
    }

    private fun buildClientPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(title("서버 정보 (공유 폰)"))
        val host = EditText(this).apply { hint = "서버 IP (예: 192.168.43.1)"; setText(settings.srvHost) }
        val port = EditText(this).apply {
            hint = "포트"; setText(settings.srvPort.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        val user = EditText(this).apply { hint = "사용자명"; setText(settings.srvUser) }
        val pass = EditText(this).apply {
            hint = "비밀번호"; setText(settings.srvPass)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(host, port, user, pass).forEach { p.addView(it) }

        vpnBtn = Button(this).apply {
            text = "VPN 연결"
            setOnClickListener {
                settings.srvHost = host.text.toString().trim()
                settings.srvPort = port.text.toString().toIntOrNull() ?: 1080
                settings.srvUser = user.text.toString().ifBlank { "user" }
                settings.srvPass = pass.text.toString().ifBlank { "changeme" }
                if (MyVpnService.isRunning) {
                    startService(Intent(this@MainActivity, MyVpnService::class.java).apply {
                        action = MyVpnService.ACTION_STOP
                    })
                    toast("VPN 해제")
                } else {
                    if (settings.srvHost.isBlank()) { toast("서버 IP 필요"); return@setOnClickListener }
                    requestVpn()
                }
            }
        }
        p.addView(vpnBtn)

        vpnStatusText = TextView(this).apply {
            text = "⚪ VPN 중지"; textSize = 16f; setPadding(0, 16, 0, 8)
        }
        p.addView(vpnStatusText)
        vpnInfoText = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }
        p.addView(vpnInfoText)

        p.addView(title("사용 안내"))
        p.addView(TextView(this).apply {
            text = """
                · 서버 폰에서 '서버 시작' 후 IP 확인
                · 이 폰을 서버 폰의 핫스팟에 연결
                · 위에 IP/포트/사용자/비밀번호 입력
                · 'VPN 연결' 누르고 권한 승인
                · 이후 모든 앱 트래픽이 서버 경유
                
                · TCP: 완전 지원
                · DNS(UDP 53): 지원
                · 기타 UDP: 미지원
            """.trimIndent()
            textSize = 13f; setPadding(0, 8, 0, 16)
        })
        return p
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_REQ)
        else startVpnService()
    }

    private fun startVpnService() {
        val i = Intent(this, MyVpnService::class.java).apply { action = MyVpnService.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toast("VPN 시작")
    }

    @Deprecated("Deprecated in API 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQ) {
            if (resultCode == RESULT_OK) startVpnService()
            else toast("VPN 권한 필요")
        }
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
        text = s; textSize = 17f; setPadding(0, 32, 0, 8)
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

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(refresh) }
}
