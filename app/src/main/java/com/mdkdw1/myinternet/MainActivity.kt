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
import android.net.wifi.p2p.WifiP2pDevice
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
    private lateinit var qrImage: ImageView
    private lateinit var wfdQrImage: ImageView

    private lateinit var vpnStatusText: TextView
    private lateinit var vpnInfoText: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var wfdStatus: TextView

    private var wfd: WifiDirectManager? = null
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
                statsText.text = "활성 ${srv.activeConnections.get()} · 총 ${srv.totalConnections.get()} · 전송 ${fmt(srv.bytesTransferred.get())}"
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
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        wfd = WifiDirectManager(this).also { it.setListener(wfdListener); it.init() }
        buildUI()
        handler.post(refresh)
    }

    private val wfdListener = object : WifiDirectManager.Listener {
        override fun onPeersChanged(peers: List<WifiP2pDevice>) {
            wfdStatus?.text = if (peers.isEmpty()) "주변 기기 없음" else "발견: ${peers.size}대"
            if (peers.isNotEmpty()) showPeerDialog(peers)
        }
        override fun onConnected(ownerIp: String?) {
            wfdStatus?.text = "🟢 Wi-Fi Direct 연결됨 (오너: $ownerIp)"
            if (!ownerIp.isNullOrBlank() && ownerIp != "0.0.0.0") {
                applyClientConfig(ownerIp, 1080, "myinternet", "myinternet")
                toast("서버 IP 자동 설정: $ownerIp")
            }
        }
        override fun onDisconnected() { wfdStatus?.text = "⚪ Wi-Fi Direct 끊김" }
        override fun onError(msg: String) { wfdStatus?.text = "⚠️ $msg"; toast(msg) }
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

        setContentView(scroll)
        ensureWfdPermission()
    }

    // ---------------- Server panel ----------------
    private fun buildServerPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        
        p.addView(title("1️⃣ 서버 시작"))
        toggleBtn = Button(this).apply {
            text = if (Socks5Service.isRunning) "■ 서버 중지" else "▶ 서버 시작"
            setOnClickListener { if (Socks5Service.isRunning) stopServer() else startServer() }
        }
        p.addView(toggleBtn)
        statusText = TextView(this).apply { text = if (Socks5Service.isRunning) "🟢 실행 중" else "⚪ 중지됨"; textSize = 16f; setPadding(0, 16, 0, 8) }
        p.addView(statusText)
        statsText = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()) }
        p.addView(statsText)

        p.addView(title("2️⃣ Wi-Fi Direct 그룹 생성 (핫스팟 아님)"))
        p.addView(Button(this).apply {
            text = "📡 이 폰을 서버로 (그룹 생성)"
            setOnClickListener {
                if (!hasWfdPermission()) { ensureWfdPermission(); return@setOnClickListener }
                wfdStatus.text = "⏳ 그룹 생성 중..."
                wfd?.createGroup()
            }
        })
        wfdStatus = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, 8, 0, 8) }
        p.addView(wfdStatus)

        p.addView(title("3️⃣ Wi-Fi Direct 연결용 QR (MAC 주소)"))
        p.addView(TextView(this).apply {
            text = "삼성 보안 정책상 MAC 주소가 02:00... 으로 보이면 QR이 생성되지 않습니다. 이 경우 일반 핫스팟+QR을 사용하세요."
            textSize = 12f; setTextColor(0xFFCC6600.toInt()); setPadding(0, 0, 0, 8)
        })
        
        wfdQrImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(500, 500)
            setPadding(0, 8, 0, 8)
        }
        p.addView(wfdQrImage)
        
        handler.postDelayed(object : Runnable {
            override fun run() {
                val mac = wfd?.fetchMyMac()
                if (mac != null && !mac.startsWith("02:00:00")) {
                    val bmp = QrHelper.generate("WFD:$mac")
                    wfdQrImage.setImageBitmap(bmp)
                }
                handler.postDelayed(this, 3000)
            }
        }, 1000)

        p.addView(title("4️⃣ 일반 핫스팟용 QR (IP)"))
        qrImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(500, 500)
            setPadding(0, 8, 0, 8)
        }
        p.addView(qrImage)
        refreshServerQr()
        
        p.addView(Button(this).apply {
            text = "IP QR 갱신"
            setOnClickListener { refreshServerQr() }
        })

        return p
    }

    private fun refreshServerQr() {
        val ip = localIps().firstOrNull { it.startsWith("192.168.43.") }
            ?: localIps().firstOrNull { it.startsWith("192.168.") && !it.startsWith("192.168.49.") }
            ?: localIps().firstOrNull()
        if (ip == null) { qrImage.setImageDrawable(null); return }
        val cfg = "$ip:${settings.port}:${settings.username}:${settings.password}"
        qrImage.setImageBitmap(QrHelper.generate(cfg))
    }

    // ---------------- Client panel ----------------
    private fun buildClientPanel(): LinearLayout {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        p.addView(title("📷 QR 스캔으로 자동 연결"))
        p.addView(Button(this).apply {
            text = "📷 서버 QR 스캔 → 자동 연결"
            setOnClickListener {
                val opts = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("서버 화면의 QR을 스캔하세요")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                }
                scanLauncher.launch(opts)
            }
        })

        p.addView(title("📡 Wi-Fi Direct 수동 검색"))
        p.addView(Button(this).apply {
            text = "🔍 주변 서버 찾기"
            setOnClickListener {
                if (!hasWfdPermission()) { ensureWfdPermission(); return@setOnClickListener }
                wfdStatus.text = "검색 중..."
                wfd?.discoverPeers()
            }
        })
        wfdStatus = TextView(this).apply { textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, 8, 0, 8) }
        p.addView(wfdStatus)

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
        if (text.startsWith("WFD:")) {
            val mac = text.removePrefix("WFD:")
            val dev = WifiP2pDevice().apply {
                deviceAddress = mac
                deviceName = "QR_Server"
            }
            wfd?.connect(dev)
            toast("Wi-Fi Direct 연결 시도 중...")
            wfdStatus?.text = "⏳ QR로 연결 요청 중..."
            return
        }

        val parts = text.trim().split(":")
        if (parts.size < 2) { toast("QR 형식 오류"); return }
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 1080
        val user = parts.getOrNull(2) ?: "myinternet"
        val pass = parts.getOrNull(3) ?: "myinternet"
        applyClientConfig(host, port, user, pass)
        toast("스캔 완료! VPN 연결...")
        handler.postDelayed({ requestVpn() }, 500)
    }

    private fun showPeerDialog(peers: List<WifiP2pDevice>) {
        val names = peers.map { "${it.deviceName} (${it.deviceAddress.takeLast(5)})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("서버 선택")
            .setItems(names) { _, i -> wfd?.connect(peers[i]); toast("연결 중...") }
            .setNegativeButton("취소", null).show()
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
        toggleBtn.text = "▶ 서버 시작"; statusText.text = "⚪ 중지됨"; toast("서버 중지")
    }
    private fun checkUpdate() {
        toast("확인 중...")
        UpdateChecker().check(versionName) { info ->
            if (info != null) AlertDialog.Builder(this)
                .setTitle("업데이트 발견")
                .setMessage("v${info.version} (현재 v$versionName)")
                .setPositiveButton("다운로드") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url))) }
                .setNegativeButton("나중에", null).show()
            else toast("최신 버전 (v$versionName)")
        }
    }
    private fun title(s: String) = TextView(this).apply { text = s; textSize = 17f; setPadding(0, 32, 0, 8); setTypeface(typeface, Typeface.BOLD) }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun hasWfdPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33) {
            val nearby = ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            fine && nearby
        } else fine
    }
    private fun ensureWfdPermission() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQ)
    }

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
        b < 1024 -> "$b B"; b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
        else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
    }
    override fun onDestroy() { super.onDestroy(); handler.removeCallbacks(refresh); wfd?.close(); wfd = null }
}
