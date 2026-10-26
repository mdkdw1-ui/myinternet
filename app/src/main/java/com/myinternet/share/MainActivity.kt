package com.myinternet.share

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
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
    private lateinit var qrView: ImageView
    private var lastQr = ""
    private var isClientMode = false
    private var pendingHost = ""
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryPermissions()
        buildUI()
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

    private fun buildUI() {
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

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad); setElevation(10f)
        }
        statusTitle = TextView(this).apply {
            text = "대기 중"; textSize = 20f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER
        }
        statusDesc = TextView(this).apply {
            text = "버튼을 눌러 시작하세요"; textSize = 14f
            setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER; setPadding(0, 8, 0, 0)
        }
        card.addView(statusTitle); card.addView(statusDesc)
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, pad, 0, pad) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnServer = Button(this).apply {
            text = "서버 시작\n(데이터 폰)"; textSize = 16f
            setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE)
            setOnClickListener { startServerMode() }
        }
        btnClient = Button(this).apply {
            text = "원터치 연결\n(QR 스캔)"; textSize = 16f
            setBackgroundColor(Color.parseColor("#2E7D32")); setTextColor(Color.WHITE)
            setOnClickListener { if (isClientMode) stopVpn() else startOneTouchConnect() }
        }
        row.addView(btnServer, LinearLayout.LayoutParams(0, (80 * resources.displayMetrics.density).toInt(), 1f).apply { setMargins(8, 0, 8, 0) })
        row.addView(btnClient, LinearLayout.LayoutParams(0, (80 * resources.displayMetrics.density).toInt(), 1f).apply { setMargins(8, 0, 8, 0) })
        root.addView(row)

        hostEt = EditText(this).apply {
            hint = "수동 IP (선택)"; inputType = InputType.TYPE_CLASS_TEXT
            visibility = View.GONE; gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        root.addView(hostEt)

        val manualBtn = Button(this).apply {
            text = "수동 IP로 연결"; textSize = 13f
            setBackgroundColor(Color.parseColor("#757575")); setTextColor(Color.WHITE)
            setOnClickListener {
                if (hostEt.visibility == View.GONE) {
                    hostEt.visibility = View.VISIBLE
                } else {
                    val h = hostEt.text.toString().trim()
                    if (h.isNotEmpty()) requestVpnAndStart(h)
                }
            }
        }
        root.addView(manualBtn, LinearLayout.LayoutParams(-1, (50 * resources.displayMetrics.density).toInt()).apply { setMargins(8, 8, 8, 0) })

        qrView = ImageView(this).apply { visibility = View.GONE; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(qrView, LinearLayout.LayoutParams((220 * resources.displayMetrics.density).toInt(), (220 * resources.displayMetrics.density).toInt()).apply { gravity = Gravity.CENTER; setMargins(0, pad, 0, pad) })

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) })

        val util = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun u(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#1565C0")); setOnClickListener { f() }
        }
        util.addView(u("로그") { startActivity(Intent(this, LogActivity::class.java)) })
        util.addView(u("설정") { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
        util.addView(u("테스트") { runTest(hostEt.text.toString().trim()) })
        root.addView(util)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    // ===== 원터치 연결: QR 스캔 시작 =====
    private fun startOneTouchConnect() {
        updateStatus("QR 스캔 준비", "서버 폰의 QR을 비춰주세요", Color.parseColor("#F57C00"))
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
                    updateStatus("IP 인식", "서버 $host", Color.parseColor("#F57C00"))
                    requestVpnAndStart(host.substringBefore(":"))
                } else {
                    updateStatus("QR 오류", "Wi-Fi 또는 IP QR이 아닙니다", Color.RED)
                }
                return
            }
            val ssid = Regex("S:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: return
            val pass = Regex("P:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: ""
            AppLog.i("ui", "QR 스캔 ssid=$ssid")
            updateStatus("Wi-Fi 연결 중...", "SSID: $ssid", Color.parseColor("#F57C00"))
            connectToWifi(ssid, pass)
        } catch (t: Throwable) {
            AppLog.e("ui", "QR 처리 실패", t)
            updateStatus("QR 오류", t.message ?: "해석 실패", Color.RED)
        }
    }

    // ===== 자동 Wi-Fi 연결 =====
    private fun connectToWifi(ssid: String, pass: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val spec = WifiNetworkSpecifier.Builder().setSsid(ssid)
                    .apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }.build()
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(spec).build()
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

                wifiCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        runOnUiThread {
                            updateStatus("Wi-Fi 연결됨", "서버 IP 확인 중...", Color.parseColor("#2E7D32"))
                            statusDesc.postDelayed({
                                val host = NetUtil.serverHost(this@MainActivity)
                                hostEt.setText(host)
                                AppLog.i("ui", "자동 IP=$host")
                                updateStatus("VPN 연결 중...", "서버 $host", Color.parseColor("#F57C00"))
                                requestVpnAndStart(host)
                            }, 1500)
                        }
                    }
                    override fun onUnavailable() {
                        runOnUiThread {
                            updateStatus("Wi-Fi 실패", "수동으로 와이파이 연결 후 다시 시도하세요", Color.RED)
                        }
                    }
                }
                cm.requestNetwork(req, wifiCallback!!)
            } else {
                updateStatus("수동 연결 필요", "설정에서 '$ssid' 연결 후 다시 시도", Color.parseColor("#F57C00"))
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (t: Throwable) {
            AppLog.e("ui", "Wi-Fi 실패", t)
            updateStatus("Wi-Fi 오류", t.message ?: "오류", Color.RED)
        }
    }

    // ===== VPN 권한 요청 → 시작 =====
    private fun requestVpnAndStart(host: String) {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            pendingHost = host
            @Suppress("DEPRECATION")
            startActivityForResult(prep, 100)
        } else {
            startVpn(host)
        }
    }

    private fun startVpn(host: String) {
        try {
            startService(Intent(this, VpnClientService::class.java).putExtra("host", host))
            isClientMode = true
            updateStatus("연결 완료", "서버 $host 로 우회 중", Color.parseColor("#2E7D32"))
        } catch (t: Throwable) {
            AppLog.e("ui", "VPN 실패", t)
            updateStatus("오류", t.message ?: "VPN 실패", Color.RED)
        }
        refresh()
    }

    private fun stopVpn() {
        startService(Intent(this, VpnClientService::class.java).setAction("stop"))
        isClientMode = false
        updateStatus("대기 중", "연결 해제됨", Color.parseColor("#333333"))
        refresh()
    }

    // ===== 서버 모드 =====
    private fun startServerMode() {
        if (ServerService.running) {
            LocalHotspot.stop()
            stopService(Intent(this, ServerService::class.java))
            updateStatus("대기 중", "서버 중지됨", Color.parseColor("#333333"))
            qrView.visibility = View.GONE
        } else {
            updateStatus("서버 시작 중...", "핫스팟 활성화", Color.parseColor("#F57C00"))
            LocalHotspot.start(this) { ok ->
                if (ok) {
                    try {
                        startForegroundService(Intent(this, ServerService::class.java))
                        updateStatus("서버 실행 중", "SSID: ${LocalHotspot.ssid}\nPW: ${LocalHotspot.pass}", Color.parseColor("#1565C0"))
                        showQr()
                    } catch (t: Throwable) {
                        updateStatus("오류", t.message ?: "실패", Color.RED)
                    }
                } else updateStatus("실패", "핫스팟 불가", Color.RED)
            }
        }
        refresh()
    }

    private fun showQr() {
        if (LocalHotspot.ssid.isEmpty()) { qrView.visibility = View.GONE; return }
        val key = "${LocalHotspot.ssid}|${LocalHotspot.pass}|${LocalHotspot.sec}"
        if (key != lastQr) {
            try { qrView.setImageBitmap(QrHelper.wifi(LocalHotspot.ssid, LocalHotspot.pass, LocalHotspot.sec)); lastQr = key } catch (_: Throwable) {}
        }
        qrView.visibility = View.VISIBLE
    }

    private fun updateStatus(t: String, d: String, c: Int) {
        statusTitle.text = t; statusTitle.setTextColor(c); statusDesc.text = d
    }

    // ===== QR 결과 & VPN 결과 처리 =====
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val qr: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (qr != null) {
            if (qr.contents == null) updateStatus("스캔 취소", "QR 스캔 취소됨", Color.GRAY)
            else handleWifiQr(qr.contents)
            return
        }
        if (requestCode == 100) {
            if (resultCode == Activity.RESULT_OK) startVpn(pendingHost)
            else updateStatus("권한 거부", "VPN 필요", Color.RED)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun runTest(host: String) {
        if (host.isEmpty()) return
        Toast.makeText(this, "테스트 중...", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = "TCP: ${Socks5Test.tcp(host, ServerService.PORT)}\nUDP: ${Socks5Test.udp(host, ServerService.PORT)}"
            runOnUiThread { AlertDialog.Builder(this).setTitle("결과").setMessage(msg).setPositiveButton("확인", null).show() }
        }.start()
    }

    private fun refresh() {
        btnServer.text = if (ServerService.running) "서버 중지" else "서버 시작\n(데이터 폰)"
        btnServer.setBackgroundColor(Color.parseColor(if (ServerService.running) "#C62828" else "#1565C0"))
        btnClient.text = if (isClientMode) "연결 해제" else "원터치 연결\n(QR 스캔)"
        btnClient.setBackgroundColor(Color.parseColor(if (isClientMode) "#C62828" else "#2E7D32"))
    }
}
