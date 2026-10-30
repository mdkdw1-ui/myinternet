#!/data/data/com.termux/files/usr/bin/bash

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0m'

echo -e "${BLUE}=== MyInternet 업데이트 (트래픽 표시 + 접속자 수 + 안전한 종료) ===${NC}"

if [ ! -f "settings.gradle.kts" ]; then
    echo -e "${RED}[오류] 프로젝트 루트에서 실행하세요.${NC}"
    exit 1
fi

# ---------------------------------------------------------
# 1. Socks5Server.kt 수정 (활성 접속자 수 카운터 추가 - 코어 로직 유지)
# ---------------------------------------------------------
cat > app/src/main/java/com/myinternet/share/Socks5Server.kt << 'EOF'
package com.myinternet.share

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Socks5Server(private val port: Int) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    // ★ 접속자 수 카운터 (UI 표시용)
    val activeConnections = AtomicInteger(0)

    fun start() {
        running = true
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        AppLog.i("srv", "SOCKS5 서버 시작 :$port")
        pool.execute {
            while (running) {
                try {
                    val c = ss.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                    if (!running) break
                    AppLog.e("srv", "accept 오류", e)
                }
            }
        }
    }

    fun stop() {
        running = false
        AppLog.i("srv", "SOCKS5 서버 중지")
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(c: Socket) {
        activeConnections.incrementAndGet()
        try {
            c.tcpNoDelay = true
            val i = DataInputStream(c.getInputStream())
            val o = c.getOutputStream()

            if (i.readUnsignedByte() != 5) return
            val n = i.readUnsignedByte()
            i.readFully(ByteArray(n))
            o.write(byteArrayOf(5, 0))
            o.flush()

            i.readUnsignedByte()
            val cmd = i.readUnsignedByte()
            i.readUnsignedByte()
            val atyp = i.readUnsignedByte()
            val host: String = when (atyp) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { i.readFully(it) }).hostAddress!!
                3 -> String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) })
                4 -> InetAddress.getByAddress(ByteArray(16).also { i.readFully(it) }).hostAddress!!
                else -> return
            }
            val dport = i.readUnsignedShort()

            when (cmd) {
                1 -> tcpConnect(c, i, o, host, dport)
                3 -> udpAssociate(c, i, o)
                else -> reply(o, 7)
            }
        } catch (e: Exception) {
            AppLog.e("srv", "세션 예외 ${e.javaClass.simpleName} ${e.message}")
        } finally {
            activeConnections.decrementAndGet()
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun reply(o: OutputStream, code: Int) {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        o.flush()
    }

    private fun resolve(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).sortedBy { if (it is Inet4Address) 0 else 1 }

    private fun tcpConnect(c: Socket, i: InputStream, o: OutputStream, host: String, dport: Int) {
        if (host.startsWith("198.18.")) {
            reply(o, 5)
            return
        }
        var r: Socket? = null
        var err: Exception? = null
        try {
            for (a in resolve(host).take(3)) {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(a, dport), if (a is Inet4Address) 7000 else 3000)
                    r = s
                    break
                } catch (e: Exception) {
                    err = e
                    try { s.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            err = e
        }
        val rs = r
        if (rs == null) {
            AppLog.e("srv", "CONNECT 실패 $host:$dport ${err?.javaClass?.simpleName} ${err?.message}")
            reply(o, 5)
            return
        }
        rs.tcpNoDelay = true
        reply(o, 0)
        val t = thread(isDaemon = true) {
            try { rs.getInputStream().copyTo(o, 65536) } catch (_: Exception) {}
            try { c.shutdownOutput() } catch (_: Exception) {}
        }
        try { i.copyTo(rs.getOutputStream(), 65536) } catch (_: Exception) {}
        try { rs.shutdownOutput() } catch (_: Exception) {}
        t.join(3000)
        try { rs.close() } catch (_: Exception) {}
    }

    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, InetAddress>()

    private fun udpAssociate(c: Socket, i: InputStream, o: OutputStream) {
        val us = DatagramSocket(0)
        try { us.receiveBufferSize = 4 shl 20 } catch (_: Exception) {}
        try { us.sendBufferSize = 4 shl 20 } catch (_: Exception) {}
        try { us.trafficClass = 0xB8 } catch (_: Exception) {}
        val bnd = c.localAddress.address
        val rep = ByteArrayOutputStream()
        rep.write(byteArrayOf(5, 0, 0, if (bnd.size == 4) 1 else 4))
        rep.write(bnd)
        rep.write(us.localPort ushr 8)
        rep.write(us.localPort and 255)
        o.write(rep.toByteArray())
        o.flush()

        val clientAddr: InetAddress = c.inetAddress
        AppLog.i("srv", "UDP ASSOCIATE ${clientAddr.hostAddress} 중계포트 ${us.localPort}")
        thread(isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val hdr = 22
            val buf = ByteArray(65535 + hdr)
            val p = DatagramPacket(buf, hdr, buf.size - hdr)
            val sp = DatagramPacket(buf, 0)
            var clientPort = -1
            var outN = 0L
            var inN = 0L
            try {
                while (true) {
                    p.setData(buf, hdr, buf.size - hdr)
                    us.receive(p)
                    val len = p.length
                    if (p.address == clientAddr && (clientPort == -1 || p.port == clientPort)) {
                        clientPort = p.port
                        if (len < 10 || buf[hdr + 2].toInt() != 0) continue
                        var idx = hdr + 3
                        val at = buf[idx++].toInt()
                        val dst: InetAddress = when (at) {
                            1 -> { val a = buf.copyOfRange(idx, idx + 4); idx += 4; InetAddress.getByAddress(a) }
                            4 -> { val a = buf.copyOfRange(idx, idx + 16); idx += 16; InetAddress.getByAddress(a) }
                            3 -> {
                                val l = buf[idx++].toInt() and 255
                                val h = String(buf, idx, l)
                                idx += l
                                try {
                                    dnsCache.getOrPut(h) { resolve(h).first() }
                                } catch (e: Exception) {
                                    continue
                                }
                            }
                            else -> continue
                        }
                        val dp = ((buf[idx].toInt() and 255) shl 8) or (buf[idx + 1].toInt() and 255)
                        idx += 2
                        sp.setData(buf, idx, hdr + len - idx)
                        sp.address = dst
                        sp.port = dp
                        us.send(sp)
                        outN++
                        if (outN == 1L) AppLog.i("srv", "UDP 첫 패킷 -> ${dst.hostAddress}:$dp")
                    } else if (clientPort != -1) {
                        val a = p.address.address
                        val hl = 4 + a.size + 2
                        val st = hdr - hl
                        buf[st] = 0
                        buf[st + 1] = 0
                        buf[st + 2] = 0
                        buf[st + 3] = (if (a.size == 4) 1 else 4).toByte()
                        System.arraycopy(a, 0, buf, st + 4, a.size)
                        buf[st + 4 + a.size] = (p.port ushr 8).toByte()
                        buf[st + 5 + a.size] = (p.port and 255).toByte()
                        sp.setData(buf, st, hl + len)
                        sp.address = clientAddr
                        sp.port = clientPort
                        us.send(sp)
                        inN++
                    }
                }
            } catch (e: Exception) {
                AppLog.i("srv", "UDP 종료 out=$outN in=$inN (${e.javaClass.simpleName})")
            }
        }

        try { while (i.read() >= 0) { } } catch (_: Exception) {}
        us.close()
    }
}
EOF

# ---------------------------------------------------------
# 2. MainActivity.kt 수정 (트래픽 표시 + 접속자 수 + 안전한 종료)
# ---------------------------------------------------------
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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import hev.htproxy.TProxyService

class MainActivity : Activity() {
    private lateinit var hostEt: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var btnServer: Button
    private lateinit var btnClient: Button
    private lateinit var btnScan: Button
    private lateinit var qrView: ImageView
    private lateinit var statsText: TextView // ★ 트래픽 표시용
    
    private var lastQr = ""
    private var isClientMode = false
    private var pendingHost = ""
    private var pendingNetwork: android.net.Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    
    private var lastTx = 0L
    private var lastRx = 0L
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryPermissions()
        buildPremiumUI()
        startStatsUpdater()
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

        // 상태 카드
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
        
        // ★ 실시간 트래픽 표시
        statsText = TextView(this).apply {
            text = "속도: - | 데이터: -"; textSize = 13f
            setTextColor(Color.parseColor("#1565C0")); gravity = Gravity.CENTER; setPadding(0, 12, 0, 0)
            setTypeface(null, Typeface.MONOSPACE)
        }
        
        statusCard.addView(statusTitle); statusCard.addView(statusDesc); statusCard.addView(statsText)
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, pad, 0, pad) })

        // 메인 버튼
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

    // ★ 1초마다 트래픽 및 접속자 수 갱신
    private fun startStatsUpdater() {
        uiHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (isClientMode) {
                        val stats = TProxyService.TProxyGetStats()
                        if (stats.size >= 4) {
                            val tx = stats[1]
                            val rx = stats[3]
                            
                            val dTx = tx - lastTx
                            val dRx = rx - lastRx
                            
                            // 포맷팅 함수 (B -> KB/MB)
                            fun formatBytes(b: Long): String {
                                if (b < 1024) return "${b}B"
                                if (b < 1024 * 1024) return String.format("%.1fKB", b / 1024.0)
                                return String.format("%.1fMB", b / (1024.0 * 1024.0))
                            }
                            
                            val upSpeed = formatBytes(dTx)
                            val downSpeed = formatBytes(dRx)
                            val totalUsed = formatBytes(tx + rx)
                            
                            statsText.text = "▼ ${downSpeed}/s  ▲ ${upSpeed}/s  |  누적: ${totalUsed}"
                            
                            lastTx = tx
                            lastRx = rx
                        }
                    } else if (ServerService.running) {
                        // 서버 모드일 때는 접속자 수 표시 (ServerService에서 가져옴)
                        statsText.text = "접속 중인 클라이언트: ${ServerService.activeClientCount}대"
                    } else {
                        statsText.text = "속도: - | 데이터: -"
                    }
                } catch (_: Throwable) {}
                uiHandler.postDelayed(this, 1000)
            }
        }, 1000)
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
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
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
            stopClientSafely()
        } else {
            hostEt.visibility = View.VISIBLE
            val host = hostEt.text.toString().trim()
            if (host.isEmpty()) { Toast.makeText(this, "IP 입력 필요", Toast.LENGTH_SHORT).show(); return }
            pendingNetwork = null
            requestVpnPermissionAndStart(host)
        }
        refresh()
    }

    // ★ 안전한 클라이언트 종료 (네트워크 꼬임 방지)
    private fun stopClientSafely() {
        try {
            startService(Intent(this, VpnClientService::class.java).setAction("stop"))
            // ★★ 중요: 묶여있던 와이파이 네트워크 바인딩 해제
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.bindProcessToNetwork(null)
                AppLog.i("ui", "bindProcessToNetwork(null) 해제 완료")
            } catch (t: Throwable) {
                AppLog.e("ui", "네트워크 바인딩 해제 실패", t)
            }
            wifiCallback?.let {
                try {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.unregisterNetworkCallback(it)
                    AppLog.i("ui", "NetworkCallback 해제 완료")
                } catch (_: Throwable) {}
            }
            wifiCallback = null
            pendingNetwork = null
            isClientMode = false
            updateStatus("대기 중", "VPN 연결 해제됨", Color.parseColor("#333333"))
        } catch (t: Throwable) {
            AppLog.e("ui", "종료 중 오류", t)
        }
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
            isClientMode = true
            lastTx = 0; lastRx = 0 // 통계 초기화
            updateStatus("클라이언트 연결됨", "서버 $host", Color.parseColor("#2E7D32"))
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

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        if (isClientMode) {
            stopClientSafely()
        }
    }
}
EOF

# ---------------------------------------------------------
# 3. ServerService.kt 수정 (접속자 수 공유)
# ---------------------------------------------------------
cat > app/src/main/java/com/myinternet/share/ServerService.kt << 'EOF'
package com.myinternet.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class ServerService : Service() {
    private var server: Socks5Server? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("srv", "ServerService onStartCommand")
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("srv", "Server", NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(this, "srv")
                .setContentTitle("MyInternet 서버 실행 중")
                .setContentText("SOCKS5 포트 $PORT")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, n)
            }
            if (wl == null) {
                wl = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myinternet:server")
                    .apply { setReferenceCounted(false); acquire() }
            }
            if (server == null) {
                server = Socks5Server(PORT).also { it.start() }
                running = true
                // ★ Socks5Server의 카운터를 ServerService에서 접근 가능하도록 캐싱
                serverInstance = server
            }
        } catch (t: Throwable) {
            AppLog.e("srv", "서버 시작 실패", t)
            running = false
            server = null
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i("srv", "ServerService onDestroy")
        server?.stop()
        server = null
        serverInstance = null
        running = false
        try { wl?.release() } catch (_: Exception) {}
        wl = null
        super.onDestroy()
    }

    companion object {
        const val PORT = 1080

        @Volatile
        var running = false

        // ★ MainActivity에서 접속자 수를 읽기 위한 인스턴스 참조
        @Volatile
        var serverInstance: Socks5Server? = null

        // ★ 실시간 접속자 수 반환
        val activeClientCount: Int
            get() = serverInstance?.activeConnections?.get() ?: 0
    }
}
EOF

# ---------------------------------------------------------
# 4. Git Push
# ---------------------------------------------------------
echo -e "${BLUE}--- Git 커밋 및 푸시 ---${NC}"
git add .
git commit -m "feat: add real-time traffic stats, client counter, and safe disconnect"
git push origin $(git rev-parse --abbrev-ref HEAD)

echo -e "${GREEN}===========================================${NC}"
echo -e "${GREEN} 완료! GitHub Actions 빌드 확인${NC}"
echo -e "${GREEN}===========================================${NC}"