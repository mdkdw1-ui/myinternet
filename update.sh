#!/data/data/com.termux/files/usr/bin/bash

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0m'

echo -e "${BLUE}=== MyInternet 클라이언트 네트워크 바인딩 수정 ===${NC}"

if [ ! -f "settings.gradle.kts" ]; then
    echo -e "${RED}[오류] 프로젝트 루트에서 실행하세요.${NC}"
    exit 1
fi

# 1. MainActivity.kt 수정 (Network 객체 전달)
cat > app/src/main/java/com/myinternet/share/MainActivity.kt << 'EOF'
package com.myinternet.share

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult

class MainActivity : Activity() {
    private lateinit var hostEt: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var btnServer: Button
    private lateinit var btnClient: Button
    private lateinit var btnScan: Button
    private lateinit var qrView: ImageView
    private var lastQr = ""
    private var isClientMode = false
    private var pendingHost = ""
    private var pendingNetwork: android.net.Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryPermissions()
        buildPremiumUI()
    }

    private fun requestNecessaryPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        perms += Manifest.permission.CAMERA
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun buildPremiumUI() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }
        root.addView(TextView(this).apply {
            text = "MyInternet"; textSize = 28f; setTextColor(Color.parseColor("#0D47A1"))
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad); setElevation(10f)
        }
        statusTitle = TextView(this).apply {
            text = "대기 중"; textSize = 20f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER
        }
        statusDesc = TextView(this).apply {
            text = "아래 버튼을 눌러 시작하세요"; textSize = 14f
            setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER; setPadding(0, 8, 0, 0)
        }
        statusCard.addView(statusTitle); statusCard.addView(statusDesc)
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, pad, 0, pad) })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnServer = Button(this).apply {
            text = "서버 시작\n(데이터 폰)"; textSize = 16f; setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE)
            setOnClickListener { startServerMode() }
        }
        btnClient = Button(this).apply {
            text = "수동 연결\n(IP 입력)"; textSize = 16f; setBackgroundColor(Color.parseColor("#2E7D32")); setTextColor(Color.WHITE)
            setOnClickListener { startClientMode() }
        }
        btnRow.addView(btnServer, LinearLayout.LayoutParams(0, (80 * resources.displayMetrics.density).toInt(), 1f).apply { setMargins(8, 0, 8, 0) })
        btnRow.addView(btnClient, LinearLayout.LayoutParams(0, (80 * resources.displayMetrics.density).toInt(), 1f).apply { setMargins(8, 0, 8, 0) })
        root.addView(btnRow)

        btnScan = Button(this).apply {
            text = "QR 코드 스캔하여 자동 연결"; textSize = 16f; setBackgroundColor(Color.parseColor("#F57C00")); setTextColor(Color.WHITE)
            setOnClickListener { startQrScanLegacy() }
        }
        root.addView(btnScan, LinearLayout.LayoutParams(-1, (60 * resources.displayMetrics.density).toInt()).apply { setMargins(8, pad, 8, 0) })

        hostEt = EditText(this).apply {
            hint = "서버 IP 주소"; inputType = InputType.TYPE_CLASS_TEXT
            setText(NetUtil.serverHost(this@MainActivity)); visibility = View.GONE
            gravity = Gravity.CENTER; setPadding(0, pad, 0, pad)
        }
        root.addView(hostEt)

        qrView = ImageView(this).apply { visibility = View.GONE; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(qrView, LinearLayout.LayoutParams((200 * resources.displayMetrics.density).toInt(), (200 * resources.displayMetrics.density).toInt()).apply { gravity = Gravity.CENTER; setMargins(0, pad, 0, pad) })

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) })

        val utilRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun utilBtn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#1565C0")); setOnClickListener { f() }
        }
        utilRow.addView(utilBtn("로그") { startActivity(Intent(this, LogActivity::class.java)) })
        utilRow.addView(utilBtn("설정") { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
        utilRow.addView(utilBtn("테스트") { runTest(hostEt.text.toString().trim()) })
        root.addView(utilRow)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun startQrScanLegacy() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("서버 폰의 Wi-Fi QR 코드를 스캔하세요")
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(false)
        integrator.initiateScan()
    }

    private fun handleWifiQr(qrText: String) {
        try {
            if (!qrText.startsWith("WIFI:")) {
                val host = qrText.trim()
                if (host.matches(Regex("^[0-9.]+(:[0-9]+)?$"))) {
                    hostEt.setText(host.substringBefore(":"))
                    requestVpnPermissionAndStart(host.substringBefore(":"))
                    return
                }
                updateStatus("QR 오류", "Wi-Fi QR이 아닙니다.", Color.RED); return
            }
            val ssid = Regex("S:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: return
            val pass = Regex("P:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: ""
            updateStatus("Wi-Fi 연결 중...", "SSID: $ssid", Color.parseColor("#F57C00"))
            connectToWifi(ssid, pass)
        } catch (t: Throwable) {
            updateStatus("QR 오류", t.message ?: "해석 실패", Color.RED)
        }
    }

    private fun connectToWifi(ssid: String, pass: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid)
                    .apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }.build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).setNetworkSpecifier(specifier).build()
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

                wifiCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        runOnUiThread {
                            AppLog.i("ui", "onAvailable network=$network")
                            pendingNetwork = network
                            updateStatus("Wi-Fi 연결됨", "IP 확인 중...", Color.parseColor("#2E7D32"))
                            statusDesc.postDelayed({
                                val host = NetUtil.serverHostFromNetwork(this@MainActivity, network)
                                hostEt.setText(host)
                                AppLog.i("ui", "QR 자동 IP = $host")
                                try {
                                    cm.bindProcessToNetwork(network)
                                    AppLog.i("ui", "MainActivity bindProcessToNetwork OK")
                                } catch (t: Throwable) {
                                    AppLog.e("ui", "bindProcessToNetwork 실패", t)
                                }
                                updateStatus("VPN 연결 중...", "서버 $host", Color.parseColor("#F57C00"))
                                requestVpnPermissionAndStart(host)
                            }, 1200)
                        }
                    }
                    override fun onUnavailable() {
                        runOnUiThread { updateStatus("Wi-Fi 실패", "수동 연결 필요", Color.RED) }
                    }
                }
                cm.requestNetwork(request, wifiCallback!!)
            } else {
                updateStatus("수동 연결", "설정에서 연결하세요", Color.parseColor("#F57C00"))
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (t: Throwable) {
            updateStatus("Wi-Fi 오류", t.message ?: "오류", Color.RED)
        }
    }

    private fun startServerMode() {
        if (ServerService.running) {
            LocalHotspot.stop(); stopService(Intent(this, ServerService::class.java))
            updateStatus("대기 중", "서버 중지됨", Color.parseColor("#333333"))
        } else {
            updateStatus("서버 시작 중...", "핫스팟 활성화 중", Color.parseColor("#F57C00"))
            LocalHotspot.start(this) { ok ->
                if (ok) {
                    try {
                        startForegroundService(Intent(this, ServerService::class.java))
                        updateStatus("서버 실행 중", "SSID: ${LocalHotspot.ssid}\nPW: ${LocalHotspot.pass}", Color.parseColor("#1565C0"))
                        showQr()
                    } catch (t: Throwable) { updateStatus("오류", t.message ?: "실패", Color.RED) }
                } else updateStatus("실패", "핫스팟 불가", Color.RED)
            }
        }
        refresh()
    }

    private fun startClientMode() {
        if (isClientMode) {
            startService(Intent(this, VpnClientService::class.java).setAction("stop"))
            isClientMode = false; updateStatus("대기 중", "연결 해제됨", Color.parseColor("#333333"))
        } else {
            hostEt.visibility = View.VISIBLE
            val host = hostEt.text.toString().trim()
            if (host.isEmpty()) { Toast.makeText(this, "IP 입력 필요", Toast.LENGTH_SHORT).show(); return }
            pendingNetwork = null
            requestVpnPermissionAndStart(host)
        }
        refresh()
    }

    private fun requestVpnPermissionAndStart(host: String) {
        val prep = VpnService.prepare(this)
        if (prep != null) { pendingHost = host; @Suppress("DEPRECATION") startActivityForResult(prep, 100) }
        else startVpn(host)
    }

    private fun startVpn(host: String) {
        try {
            val intent = Intent(this, VpnClientService::class.java).putExtra("host", host)
            pendingNetwork?.let { intent.putExtra("network", it) }
            startService(intent)
            isClientMode = true; updateStatus("클라이언트 연결됨", "서버 $host", Color.parseColor("#2E7D32"))
        } catch (t: Throwable) { updateStatus("오류", t.message ?: "VPN 실패", Color.RED) }
    }

    private fun updateStatus(title: String, desc: String, color: Int) {
        statusTitle.text = title; statusTitle.setTextColor(color); statusDesc.text = desc
    }

    private fun showQr() {
        if (LocalHotspot.ssid.isEmpty()) { qrView.visibility = View.GONE; return }
        val key = "${LocalHotspot.ssid}|${LocalHotspot.pass}|${LocalHotspot.sec}"
        if (key != lastQr) {
            try { qrView.setImageBitmap(QrHelper.wifi(LocalHotspot.ssid, LocalHotspot.pass, LocalHotspot.sec)); lastQr = key } catch (_: Throwable) {}
        }
        qrView.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                updateStatus("스캔 취소", "QR 스캔이 취소되었습니다.", Color.GRAY)
            } else {
                handleWifiQr(result.contents)
            }
        } else if (requestCode == 100) {
            if (resultCode == Activity.RESULT_OK) startVpn(pendingHost)
            else updateStatus("권한 거부", "VPN 필요", Color.RED)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun runTest(host: String) {
        if (host.isEmpty()) return
        Toast.makeText(this, "테스트 중...", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = "대상: $host\nTCP: ${Socks5Test.tcp(host, ServerService.PORT)}\nUDP: ${Socks5Test.udp(host, ServerService.PORT)}"
            runOnUiThread { AlertDialog.Builder(this).setTitle("결과").setMessage(msg).setPositiveButton("확인", null).show() }
        }.start()
    }

    private fun refresh() {
        if (ServerService.running) {
            btnServer.text = "서버 중지"; btnServer.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            btnServer.text = "서버 시작\n(데이터 폰)"; btnServer.setBackgroundColor(Color.parseColor("#1565C0"))
        }
        if (isClientMode) {
            btnClient.text = "연결 해제"; btnClient.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            btnClient.text = "수동 연결\n(IP 입력)"; btnClient.setBackgroundColor(Color.parseColor("#2E7D32"))
        }
    }
}
EOF

# 2. VpnClientService.kt 수정 (전달받은 Network에 바인딩)
cat > app/src/main/java/com/myinternet/share/VpnClientService.kt << 'EOF'
package com.myinternet.share

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import kotlin.concurrent.thread

class VpnClientService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var statsThread: Thread? = null
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("vpn", "onStartCommand action=${intent?.action} host=${intent?.getStringExtra("host")}")
        if (intent?.action == "stop") {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra("host") ?: return START_NOT_STICKY
        
        // ★★★ VPN 프로세스도 Wi-Fi 네트워크를 쓰도록 바인딩 (핵심 수정) ★★★
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = intent.getParcelableExtra<Network>("network")
            if (network != null) {
                try {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.bindProcessToNetwork(network)
                    AppLog.i("vpn", "VPN 프로세스가 Wi-Fi 네트워크에 바인딩됨: $network")
                } catch (t: Throwable) {
                    AppLog.e("vpn", "VPN 프로세스 네트워크 바인딩 실패", t)
                }
            }
        }

        startTunnel(host)
        return START_NOT_STICKY
    }

    private fun startTunnel(host: String) {
        stopTunnel()
        try {
            AppLog.i("vpn", "native 로드 시도")
            Class.forName("hev.htproxy.TProxyService")
            AppLog.i("vpn", "native 로드 OK")
            val fd = Builder()
                .setSession("MyInternet")
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("198.18.0.2")
                .addDisallowedApplication(packageName)
                .establish()
            if (fd == null) {
                AppLog.e("vpn", "establish() 가 null: VPN 권한이 없거나 다른 VPN이 우선")
                return
            }
            pfd = fd
            AppLog.i("vpn", "TUN 생성 fd=${fd.fd}")

            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                           else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "myinternet").apply { setReferenceCounted(false); acquire() }
            } catch (t: Throwable) {
                AppLog.e("vpn", "Wi-Fi 락 실패", t)
            }

            val logFile = File(filesDir, "hev.log")
            logFile.delete()
            val cfg = File(filesDir, "hev.yml")
            cfg.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 198.18.0.1
                socks5:
                  address: $host
                  port: ${ServerService.PORT}
                  udp: 'udp'
                  pipeline: true
                mapdns:
                  address: 198.18.0.2
                  port: 53
                  network: 100.64.0.0
                  netmask: 255.192.0.0
                  cache-size: 10000
                misc:
                  log-file: ${logFile.absolutePath}
                  log-level: warn
                """.trimIndent() + "\n"
            )
            AppLog.i("vpn", "hev 설정:\n" + cfg.readText())
            val r = TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)
            started = true
            AppLog.i("vpn", "TProxyStartService 반환: $r")
            startStats()
        } catch (t: Throwable) {
            AppLog.e("vpn", "터널 시작 실패", t)
            stopTunnel()
        }
    }

    private fun startStats() {
        statsThread = thread(isDaemon = true) {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(5000)
                    val s = TProxyService.TProxyGetStats()
                    if (s.size >= 4) {
                        AppLog.i("hev", "통계 tx=${s[0]}pkt/${s[1]}B rx=${s[2]}pkt/${s[3]}B")
                    }
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                AppLog.e("hev", "통계 오류", t)
            }
        }
    }

    private fun stopTunnel() {
        statsThread?.interrupt()
        statsThread = null
        if (started) {
            started = false
            try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        }
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onRevoke() {
        AppLog.i("vpn", "onRevoke")
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
EOF

# 3. Git Push
git add .
git commit -m "fix: bind VpnService process to the requested Wi-Fi network"
git push origin $(git rev-parse --abbrev-ref HEAD)

echo -e "${GREEN}===========================================${NC}"
echo -e "${GREEN} 완료! GitHub Actions 빌드 후 다시 테스트해보세요.${NC}"
echo -e "${GREEN}===========================================${NC}"