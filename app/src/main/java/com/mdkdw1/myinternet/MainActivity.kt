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
import android.net.wifi.WifiManager
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

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var ipText: TextView
    private lateinit var toggleBtn: Button

    private lateinit var vpnStatusText: TextView
    private lateinit var vpnInfoText: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val VPN_REQ = 200

    private val versionName: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.000" }
        catch (_: Exception) { "0.000" }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val srv = Socks5Service.server
            if (srv != null && Socks5Service.isRunning) {
                statsText.text = "활성 ${srv.activeConnections.get()} · 총 ${srv.totalConnections.get()} · " +
                        "전송 ${fmt(srv.bytesTransferred.get())}"
            } else statsText.text = ""
            if (MyVpnService.isRunning) {
                vpnStatusText.text = "🟢 VPN 연결됨"
                vpnInfoText.text = "서버: ${settings.srvHost}:${settings.srvPort}"
            } else {
                vpnStatusText.text = "⚪ VPN 중지"
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

        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; setPadding(0, 0, 0, 16) }
        val rbServer = RadioButton(this).apply { text = "서버 (공유 폰)"; id = View.generateViewId(); isChecked = true }
        val rbClient = RadioButton(this).apply { text = "클라이언트"; id = View.generateViewId() }
        modeGroup.addView(rbServer); modeGroup.addView(rbClient)
        modeGroup.setOnCheckedChangeListener { _, id ->
            val isServer = id == rbServer.id
            serverPanel.visibility = if (isServer) View.VISIBLE else View.GONE
            clientPanel.visibility = if (isServer) View.GONE else View.VISIBLE
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
        p.addView(title("1️⃣ 서버 시작"))
        p.addView(TextView(this).apply {
            text = "아래 버튼만 누르면 됩니다. 클라이언트가 자동으로 찾습니다."
            textSize = 13f; setPadding(0, 0, 0, 8)
        })
        toggleBtn = Button(this).apply {
            text = if (Socks5Service.isRunning) "■ 서버 중지" else "▶ 서버 시작"
            setOnClickListener { if (Socks5Service.isRunning) stopServer() else startServer() }
        }
        p.addView(toggleBtn)
        statusText = TextView(this).apply {
            text = if (Socks5Service.isRunning) "🟢 실행 중" else "⚪ 중지됨"
            textSize = 16f; setPadding(0, 16, 0, 8)
        }
        p.addView(statusText)
        statsText = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }
        p.addView(statsText)

        p.addView(title("2️⃣ 클라이언트에 정보 전달 (선택)"))
        ipText = TextView(this).apply {
            text = localIps().joinToString("  ").ifEmpty { "IP 없음 (핫스팟 확인)" }
            typeface = Typeface.MONOSPACE; textSize = 15f; setPadding(0, 8, 0, 8)
        }
        p.addView(ipText)
        p.addView(Button(this).apply {
            text = "📋 설정 문자열 복사 (자동 발견 실패 시 사용)"
            setOnClickListener {
                val ip = localIps().firstOrNull() ?: "0.0.0.0"
                val cfg = "$ip:${settings.port}:${settings.username}:${settings.password}"
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("cfg", cfg))
                toast("복사됨 → 클라이언트에서 '붙여넣기' 누르세요")
            }
        })

        p.addView(title("고급 설정 (기본값 사용 권장)"))
        val port = EditText(this).apply {
            hint = "포트"; setText(settings.port.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        val user = EditText(this).apply { hint = "사용자명"; setText(settings.username) }
        val pass = EditText(this).apply {
            hint = "비밀번호"; setText(settings.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(port, user, pass).forEach { p.addView(it) }
        p.addView(Button(this).apply {
            text = "저장"
            setOnClickListener {
                settings.port = port.text.toString().toIntOrNull() ?: 1080
                settings.username = user.text.toString().ifBlank { "myinternet" }
                settings.password = pass.text.toString().ifBlank { "myinternet" }
                toast("저장됨. 서버 재시작 시 적용")
                if (Socks5Service.isRunning) { stopServer(); startServer() }
            }
        })
        return p
    }

    private fun buildClientPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(title("🚀 원터치 연결"))
        p.addView(TextView(this).apply {
            text = "서버 폰의 핫스팟에 연결된 상태에서 아래 버튼만 누르세요."
            textSize = 13f; setPadding(0, 0, 0, 8)
        })
        p.addView(Button(this).apply {
            text = "🔍 자동 연결 (권장)"
            setOnClickListener { autoConnect() }
        })
        p.addView(Button(this).apply {
            text = "📋 클립보드에서 설정 붙여넣기"
            setOnClickListener { pasteConfig() }
        })

        p.addView(title("수동 설정 (자동 실패 시)"))
        hostInput = EditText(this).apply { hint = "서버 IP"; setText(settings.srvHost) }
        portInput = EditText(this).apply {
            hint = "포트"; setText(settings.srvPort.toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        userInput = EditText(this).apply { hint = "사용자명"; setText(settings.srvUser) }
        passInput = EditText(this).apply {
            hint = "비밀번호"; setText(settings.srvPass)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(hostInput, portInput, userInput, passInput).forEach { p.addView(it) }
        p.addView(Button(this).apply {
            text = "VPN 연결"
            setOnClickListener {
                saveClientFields()
                if (MyVpnService.isRunning) stopVpn() else requestVpn()
            }
        })

        vpnStatusText = TextView(this).apply {
            text = "⚪ VPN 중지"; textSize = 16f; setPadding(0, 16, 0, 8)
        }
        p.addView(vpnStatusText)
        vpnInfoText = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }
        p.addView(vpnInfoText)

        p.addView(title("사용 안내"))
        p.addView(TextView(this).apply {
            text = """
                · TCP 완전 지원 / DNS(UDP 53) 지원
                · YouTube 등 QUIC(UDP)은 일부 미지원
                · 두 폰 모두 이 앱 설치 필요
                · 서버 폰은 '서버 시작' 상태여야 함
            """.trimIndent()
            textSize = 13f; setPadding(0, 8, 0, 16)
        })
        return p
    }

    private fun autoConnect() {
        if (MyVpnService.isRunning) { stopVpn(); return }
        vpnStatusText.text = "🔍 서버 찾는 중 (최대 5초)..."
        vpnInfoText.text = ""
        DiscoveryClient().discover(5000) { servers ->
            val s = servers.firstOrNull()
            if (s != null) {
                applyClientConfig(s.host, s.port, s.user, s.pass)
                vpnStatusText.text = "✅ 자동 발견: ${s.host}:${s.port}"
                requestVpn()
            } else {
                val gw = detectGateway()
                if (gw != null) {
                    applyClientConfig(gw, 1080, "myinternet", "myinternet")
                    vpnStatusText.text = "⚠️ 게이트웨이로 시도: $gw"
                    requestVpn()
                } else {
                    vpnStatusText.text = "❌ 서버 못 찾음"
                    toast("자동 발견 실패. 수동 입력 또는 '붙여넣기'를 사용하세요")
                }
            }
        }
    }

    private fun applyClientConfig(host: String, port: Int, user: String, pass: String) {
        hostInput.setText(host); portInput.setText(port.toString())
        userInput.setText(user); passInput.setText(pass)
        settings.srvHost = host; settings.srvPort = port
        settings.srvUser = user; settings.srvPass = pass
    }

    private fun pasteConfig() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val txt = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        val parts = txt.split(":")
        if (parts.size >= 4) {
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: 1080
            applyClientConfig(host, port, parts[2], parts[3])
            toast("붙여넣기 완료 → '자동 연결' 또는 'VPN 연결'")
        } else toast("형식 오류: IP:포트:사용자:비밀번호")
    }

    private fun saveClientFields() {
        settings.srvHost = hostInput.text.toString().trim()
        settings.srvPort = portInput.text.toString().toIntOrNull() ?: 1080
        settings.srvUser = userInput.text.toString().ifBlank { "myinternet" }
        settings.srvPass = passInput.text.toString().ifBlank { "myinternet" }
    }

    private fun detectGateway(): String? = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val gw = wm.dhcpInfo.gateway
        if (gw == 0) null
        else "${gw and 0xff}.${(gw shr 8) and 0xff}.${(gw shr 16) and 0xff}.${(gw shr 24) and 0xff}"
    } catch (_: Exception) { null }

    private fun requestVpn() {
        if (settings.srvHost.isBlank()) { toast("서버 정보 없음"); return }
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_REQ) else startVpnService()
    }

    private fun startVpnService() {
        val i = Intent(this, MyVpnService::class.java).apply { action = MyVpnService.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toast("VPN 시작")
    }

    private fun stopVpn() {
        startService(Intent(this, MyVpnService::class.java).apply { action = MyVpnService.ACTION_STOP })
        toast("VPN 해제")
    }

    @Deprecated("Deprecated in API 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQ) {
            if (resultCode == RESULT_OK) startVpnService() else toast("VPN 권한 필요")
        }
    }

    private fun startServer() {
        val i = Intent(this, Socks5Service::class.java).apply { action = Socks5Service.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toggleBtn.text = "■ 서버 중지"; statusText.text = "🟢 실행 중"; toast("서버 시작")
    }

    private fun stopServer() {
        startService(Intent(this, Socks5Service::class.java).apply { action = Socks5Service.ACTION_STOP })
        toggleBtn.text = "▶ 서버 시작"; statusText.text = "⚪ 중지됨"; toast("서버 중지")
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
