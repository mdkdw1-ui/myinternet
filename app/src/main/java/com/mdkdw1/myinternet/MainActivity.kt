package com.mdkdw1.myinternet

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {
    private lateinit var settings: SettingsManager
    private lateinit var serverPanel: LinearLayout
    private lateinit var clientPanel: LinearLayout

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var hotspotInfoText: TextView
    private lateinit var qrImage: ImageView

    private lateinit var vpnStatusText: TextView
    private lateinit var vpnInfoText: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var clientStatusText: TextView

    private var localHotspot: LocalHotspotManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val VPN_REQ = 200
    private val PERM_REQ = 300

    private val versionName: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0.000" }
        catch (_: Exception) { "0.000" }
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        handleScannedText(text)
    }

    private val refresh = object : Runnable {
        override fun run() {
            val srv = Socks5Service.server
            if (srv != null && Socks5Service.isRunning)
                statsText.text = "활성 ${srv.activeConnections.get()} · 총 ${srv.totalConnections.get()}"
            else statsText.text = ""

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
        CrashHandler.init(this)
        settings = SettingsManager(this)
        localHotspot = LocalHotspotManager(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)

        buildUI()
        handler.post(refresh)
        ensurePermissions()
    }

    private fun ensurePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQ)
    }

    private fun buildUI() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 48)
        }
        scroll.addView(root)
        root.addView(TextView(this).apply { text = "🌐 MyInternet"; textSize = 26f })
        root.addView(TextView(this).apply { text = "v$versionName"; textSize = 13f; setTextColor(0xFF888888.toInt()); setPadding(0, 0, 0, 20) })

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
        root.addView(serverPanel); root.addView(clientPanel)

        root.addView(title("업데이트"))
        root.addView(Button(this).apply { text = "업데이트 확인"; setOnClickListener { checkUpdate() } })

        root.addView(title("디버그"))
        root.addView(Button(this).apply {
            text = "📜 앱 로그 보기 (크래시 원인 확인)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LogcatActivity::class.java))
            }
        })

        setContentView(scroll)
    }

    private fun buildServerPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        p.addView(title("1️⃣ 원터치 서버 시작 (프록시 + 핫스팟)"))
        toggleBtn = Button(this).apply {
            text = if (Socks5Service.isRunning) "■ 서버 중지" else "▶ 서버 시작 (한 번에)"
            setOnClickListener { 
                if (Socks5Service.isRunning) stopServer() else startServerAndHotspot() 
            }
        }
        p.addView(toggleBtn)
        statusText = TextView(this).apply { text = if (Socks5Service.isRunning) "🟢 실행 중" else "⚪ 중지됨"; textSize = 16f; setPadding(0, 16, 0, 8) }
        p.addView(statusText)
        statsText = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }
        p.addView(statsText)

        p.addView(title("2️⃣ 클라이언트 접속 정보 (QR)"))
        hotspotInfoText = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 8); typeface = Typeface.MONOSPACE }
        p.addView(hotspotInfoText)
        qrImage = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(500, 500) }
        p.addView(qrImage)

        return p
    }

    private fun startServerAndHotspot() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= 33) {
            ensurePermissions()
            toast("권한을 허용한 후 다시 눌러주세요")
            return
        }
        
        startServer()
        
        localHotspot?.startHotspot(object : LocalHotspotManager.HotspotCallback {
            override fun onHotspotStarted(ssid: String, password: String) {
                handler.postDelayed({
                    val myIp = getServerHotspotIp()
                    hotspotInfoText.text = "SSID: $ssid\nPW: $password\n내 IP: $myIp"
                    val cfg = "HOTSPOT:$ssid:$password:$myIp"
                    qrImage.setImageBitmap(QrHelper.generate(cfg))
                }, 1000)
            }
            override fun onHotspotFailed(reason: String) {
                hotspotInfoText.text = "핫스팟 실패: $reason"
            }
            override fun onClientConnected() {}
            override fun onClientDisconnected() {}
        })
    }

    private fun buildClientPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        p.addView(title("📷 QR 스캔으로 핫스팟 연결"))
        p.addView(Button(this).apply {
            text = "📷 서버 QR 스캔"
            setOnClickListener {
                val opts = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("서버 화면의 QR을 스캔하세요")
                    setBeepEnabled(false)
                }
                scanLauncher.launch(opts)
            }
        })
        clientStatusText = TextView(this).apply { textSize = 13f; setPadding(0, 8, 0, 8) }
        p.addView(clientStatusText)

        p.addView(title("수동 설정 (VPN)"))
        hostInput = EditText(this).apply { hint = "서버 IP"; setText(settings.srvHost) }
        portInput = EditText(this).apply { hint = "포트"; setText(settings.srvPort.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        userInput = EditText(this).apply { hint = "사용자명"; setText(settings.srvUser) }
        passInput = EditText(this).apply { hint = "비밀번호"; setText(settings.srvPass); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        listOf(hostInput, portInput, userInput, passInput).forEach { p.addView(it) }
        
        p.addView(Button(this).apply {
            text = "VPN 연결"
            setOnClickListener {
                saveClientFields()
                if (MyVpnService.isRunning) stopVpn() else requestVpn()
            }
        })
        vpnStatusText = TextView(this).apply { text = "⚪ VPN 중지"; textSize = 16f; setPadding(0, 16, 0, 8) }
        p.addView(vpnStatusText)
        vpnInfoText = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }
        p.addView(vpnInfoText)

        return p
    }

    private fun handleScannedText(text: String) {
        if (text.startsWith("HOTSPOT:")) {
            val parts = text.removePrefix("HOTSPOT:").split(":")
            if (parts.size >= 2) {
                val ssid = parts[0]
                val pw = parts[1]
                val qrIp = parts.getOrNull(2)
                clientStatusText.text = "핫스팟 연결 중: $ssid"
                localHotspot?.cancelPending()
                localHotspot?.connectToHotspot(ssid, pw, object : LocalHotspotManager.HotspotCallback {
                    override fun onHotspotStarted(ssid: String, password: String) {}
                    override fun onHotspotFailed(reason: String) {
                        clientStatusText.text = "연결 실패: $reason"
                        toast("핫스팟 실패: $reason")
                    }
                    override fun onClientConnected() {
                        val serverIp = findRealGatewayIp() ?: getGatewayIp() ?: qrIp ?: "192.168.43.1"
                        clientStatusText.text = "✅ 핫스팟 연결됨. 서버 IP: $serverIp"
                        applyClientConfig(serverIp, 1080, "myinternet", "myinternet")
                        
                        // [수정] 서버 포트(1080)가 열려있는지 확인 후 VPN 시작
                        clientStatusText.text = "서버 포트 확인 중..."
                        Thread {
                            var reachable = false
                            try {
                                val socket = java.net.Socket()
                                socket.connect(java.net.InetSocketAddress(serverIp, 1080), 5000) // 5초 대기
                                socket.close()
                                reachable = true
                            } catch (e: Exception) {}
                            
                            runOnUiThread {
                                if (reachable) {
                                    clientStatusText.text = "✅ 서버 접속 성공. VPN 연결..."
                                    handler.postDelayed({ requestVpn() }, 500)
                                } else {
                                    clientStatusText.text = "❌ 서버(1080) 접속 실패. 서버 폰의 앱이 켜져 있는지 확인하세요."
                                    toast("서버 포트(1080)가 막혀있습니다.")
                                }
                            }
                        }.start()
                    }
                    override fun onClientDisconnected() { clientStatusText.text = "연결 끊김" }
                })
            }
        } else {
            val parts = text.trim().split(":")
            if (parts.size < 2) return
            applyClientConfig(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 1080,
                parts.getOrNull(2) ?: "myinternet", parts.getOrNull(3) ?: "myinternet")
            toast("IP 설정 완료. VPN 연결...")
            handler.postDelayed({ requestVpn() }, 500)
        }
    }

    private fun getServerHotspotIp(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback) {
                    val name = intf.name
                    if (name.startsWith("ap") || name.startsWith("swlan") || name.contains("wlan1") || name.contains("softap")) {
                        for (addr in intf.inetAddresses.toList()) {
                            if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                                val ip = addr.hostAddress
                                if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10."))) return ip
                            }
                        }
                    }
                }
            }
            for (intf in interfaces) {
                for (addr in intf.inetAddresses.toList()) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (ip != null && ip.startsWith("10.204.")) return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }

    private fun getGatewayIp(): String? {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ip = wm.dhcpInfo.gateway
            if (ip != 0) return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
        } catch (_: Exception) {}
        return null
    }

    private fun applyClientConfig(host: String, port: Int, user: String, pass: String) {
        hostInput.setText(host); portInput.setText(port.toString()); userInput.setText(user); passInput.setText(pass)
        settings.srvHost = host; settings.srvPort = port; settings.srvUser = user; settings.srvPass = pass
    }

    private fun saveClientFields() {
        settings.srvHost = hostInput.text.toString().trim()
        settings.srvPort = portInput.text.toString().toIntOrNull() ?: 1080
        settings.srvUser = userInput.text.toString().ifBlank { "myinternet" }
        settings.srvPass = passInput.text.toString().ifBlank { "myinternet" }
    }

    private fun findRealGatewayIp(): String? {
        try {
            // 1. ARP 테이블에서 10.x.x.1 또는 192.168.x.1 형태 찾기
            val process = Runtime.getRuntime().exec("ip neigh")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.contains("REACHABLE") || line.contains("STALE")) {
                    val parts = line.split(" ")
                    if (parts.isNotEmpty()) {
                        val ip = parts[0]
                        if ((ip.startsWith("10.") || ip.startsWith("192.168.")) && ip.endsWith(".1")) {
                            return ip
                        }
                    }
                }
                line = reader.readLine()
            }
            reader.close()
        } catch (_: Exception) {}

        try {
            // 2. 네트워크 인터페이스에서 게이트웨이 유추
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback && (intf.name.contains("wlan") || intf.name.contains("ap"))) {
                    for (addr in intf.inetAddresses.toList()) {
                        if (addr is java.net.Inet4Address) {
                            val ip = addr.hostAddress
                            if (ip != null && (ip.startsWith("10.") || ip.startsWith("192.168."))) {
                                // IP의 마지막 자리를 .1로 변경 (예: 10.204.228.226 -> 10.204.228.1)
                                val prefix = ip.substringBeforeLast(".")
                                return "$prefix.1"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

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
        if (requestCode == VPN_REQ) { if (resultCode == RESULT_OK) startVpnService() else toast("VPN 권한 필요") }
    }
    private fun startServer() {
        val i = Intent(this, Socks5Service::class.java).apply { action = Socks5Service.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toggleBtn.text = "■ 서버 중지"; statusText.text = "🟢 실행 중"; toast("서버 시작")
    }
    private fun stopServer() {
        startService(Intent(this, Socks5Service::class.java).apply { action = Socks5Service.ACTION_STOP })
        localHotspot?.stopHotspot()
        toggleBtn.text = "▶ 서버 시작 (한 번에)"; statusText.text = "⚪ 중지됨"; hotspotInfoText.text = "중지됨"
        toast("서버 및 핫스팟 중지")
    }
    private fun checkUpdate() {
        toast("확인 중...")
        UpdateChecker().check(versionName) { info ->
            if (info != null) AlertDialog.Builder(this)
                .setTitle("업데이트 발견").setMessage("v${info.version} (현재 v$versionName)")
                .setPositiveButton("다운로드") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url))) }
                .setNegativeButton("나중에", null).show()
            else toast("최신 버전 (v$versionName)")
        }
    }
    private fun title(s: String) = TextView(this).apply { text = s; textSize = 17f; setPadding(0, 32, 0, 8); setTypeface(typeface, Typeface.BOLD) }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(refresh); localHotspot?.stopHotspot() }
}
