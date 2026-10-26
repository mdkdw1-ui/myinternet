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
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {
    private lateinit var hostEt: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var btnServer: Button
    private lateinit var btnClient: Button
    private lateinit var btnScan: Button
    private lateinit var qrView: ImageView
    private var lastQr = ""
    private var isClientMode = false
    private var pendingHost: String = ""
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleWifiQr(result.contents)
        } else {
            updateStatus("스캔 취소", "QR 스캔이 취소되었습니다.", Color.parseColor("#666666"))
        }
    }

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

        val title = TextView(this).apply {
            text = "MyInternet"
            textSize = 28f
            setTextColor(Color.parseColor("#0D47A1"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
            setElevation(10f)
        }
        val cardParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, pad, 0, pad) }

        statusTitle = TextView(this).apply {
            text = "대기 중"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
        }
        statusDesc = TextView(this).apply {
            text = "아래 버튼을 눌러 시작하세요"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusDesc)
        root.addView(statusCard, cardParams)

        val btnContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        btnServer = Button(this).apply {
            text = "서버 시작\n(데이터 폰)"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener { startServerMode() }
        }
        btnClient = Button(this).apply {
            text = "수동 연결\n(IP 입력)"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener { startClientMode() }
        }

        val btnParams = LinearLayout.LayoutParams(0, (80 * resources.displayMetrics.density).toInt(), 1f)
        btnParams.setMargins(8, 0, 8, 0)
        btnContainer.addView(btnServer, btnParams)
        btnContainer.addView(btnClient, btnParams)
        root.addView(btnContainer)

        btnScan = Button(this).apply {
            text = "QR 코드 스캔하여 자동 연결"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#F57C00"))
            setTextColor(Color.WHITE)
            setOnClickListener { startQrScan() }
        }
        val scanParams = LinearLayout.LayoutParams(-1, (60 * resources.displayMetrics.density).toInt()).apply { setMargins(8, pad, 8, 0) }
        root.addView(btnScan, scanParams)

        hostEt = EditText(this).apply {
            hint = "서버 IP 주소 입력"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(NetUtil.serverHost(this@MainActivity))
            visibility = View.GONE
            setPadding(0, pad, 0, pad)
            gravity = Gravity.CENTER
        }
        root.addView(hostEt)

        qrView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val qs = (200 * resources.displayMetrics.density).toInt()
        val qrParams = LinearLayout.LayoutParams(qs, qs).apply { gravity = Gravity.CENTER; setMargins(0, pad, 0, pad) }
        root.addView(qrView, qrParams)

        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(spacer)

        val utilRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun utilBtn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            textSize = 12f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#1565C0"))
            setOnClickListener { f() }
        }
        utilRow.addView(utilBtn("로그 확인") { startActivity(Intent(this, LogActivity::class.java)) })
        utilRow.addView(utilBtn("네트워크 설정") { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
        utilRow.addView(utilBtn("진단 테스트") { runTest(hostEt.text.toString().trim()) })

        root.addView(utilRow)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    // ---------- QR 스캔 ----------
    private fun startQrScan() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("서버 폰의 Wi-Fi QR 코드를 스캔하세요")
        options.setBeepEnabled(false)
        options.setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    private fun handleWifiQr(qrText: String) {
        try {
            if (!qrText.startsWith("WIFI:")) {
                val host = qrText.trim()
                if (host.matches(Regex("^[0-9.]+(:[0-9]+)?$"))) {
                    hostEt.setText(host.substringBefore(":"))
                    updateStatus("IP 인식", "서버 $host 로 연결합니다.", Color.parseColor("#F57C00"))
                    requestVpnPermissionAndStart(host.substringBefore(":"))
                    return
                }
                updateStatus("QR 오류", "Wi-Fi 또는 IP QR 코드가 아닙니다.", Color.RED)
                return
            }
            val ssid = Regex("S:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: return
            val pass = Regex("P:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: ""

            AppLog.i("ui", "QR 스캔 성공 ssid=$ssid")
            updateStatus("Wi-Fi 연결 중...", "SSID: $ssid", Color.parseColor("#F57C00"))
            connectToWifi(ssid, pass)
        } catch (t: Throwable) {
            AppLog.e("ui", "QR 처리 실패", t)
            updateStatus("QR 오류", "QR 해석 실패: ${t.message}", Color.RED)
        }
    }

    private fun connectToWifi(ssid: String, pass: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }
                    .build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build()

                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

                wifiCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        runOnUiThread {
                            updateStatus("Wi-Fi 연결됨", "서버 IP 확인 중...", Color.parseColor("#2E7D32"))
                            statusDesc.postDelayed({
                                val host = NetUtil.serverHost(this@MainActivity)
                                hostEt.setText(host)
                                AppLog.i("ui", "QR 자동 IP = $host")
                                updateStatus("VPN 연결 중...", "서버 $host", Color.parseColor("#F57C00"))
                                requestVpnPermissionAndStart(host)
                            }, 1500)
                        }
                    }

                    override fun onUnavailable() {
                        runOnUiThread {
                            updateStatus("Wi-Fi 실패", "자동 연결 실패. 수동으로 와이파이를 연결해주세요.", Color.RED)
                        }
                    }
                }
                cm.requestNetwork(request, wifiCallback!!)
            } else {
                updateStatus("수동 연결 필요", "설정에서 '$ssid' 에 연결 후 다시 시도하세요.", Color.parseColor("#F57C00"))
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (t: Throwable) {
            AppLog.e("ui", "Wi-Fi 연결 실패", t)
            updateStatus("Wi-Fi 오류", t.message ?: "알 수 없는 오류", Color.RED)
        }
    }

    // ---------- 서버 모드 ----------
    private fun startServerMode() {
        if (ServerService.running) {
            LocalHotspot.stop()
            stopService(Intent(this, ServerService::class.java))
            updateStatus("대기 중", "서버가 중지되었습니다.", Color.parseColor("#333333"))
        } else {
            updateStatus("서버 시작 중...", "로컬 핫스팟을 활성화하고 있습니다.", Color.parseColor("#F57C00"))
            LocalHotspot.start(this) { ok ->
                if (ok) {
                    try {
                        startForegroundService(Intent(this, ServerService::class.java))
                        updateStatus(
                            "서버 실행 중",
                            "핫스팟: ${LocalHotspot.ssid}\n비밀번호: ${LocalHotspot.pass}",
                            Color.parseColor("#1565C0")
                        )
                        showQr()
                    } catch (t: Throwable) {
                        AppLog.e("ui", "서버 시작 실패", t)
                        updateStatus("오류", "서버 시작 실패: ${t.message}", Color.RED)
                    }
                } else {
                    updateStatus("실패", "핫스팟을 켤 수 없습니다. 설정을 확인하세요.", Color.RED)
                }
            }
        }
        refresh()
    }

    // ---------- 클라이언트 수동 모드 ----------
    private fun startClientMode() {
        if (isClientMode) {
            startService(Intent(this, VpnClientService::class.java).setAction("stop"))
            isClientMode = false
            updateStatus("대기 중", "VPN 연결이 해제되었습니다.", Color.parseColor("#333333"))
        } else {
            hostEt.visibility = View.VISIBLE
            val host = hostEt.text.toString().trim()
            if (host.isEmpty()) {
                Toast.makeText(this, "서버 IP를 입력하거나 QR을 스캔하세요", Toast.LENGTH_SHORT).show()
                return
            }
            updateStatus("연결 중...", "VPN 터널을 생성하고 있습니다.", Color.parseColor("#F57C00"))
            requestVpnPermissionAndStart(host)
        }
        refresh()
    }

    private fun requestVpnPermissionAndStart(host: String) {
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
            updateStatus("클라이언트 연결됨", "서버 $host 로 우회 중", Color.parseColor("#2E7D32"))
        } catch (t: Throwable) {
            AppLog.e("ui", "VPN 시작 실패", t)
            updateStatus("오류", "VPN 시작 실패: ${t.message}", Color.RED)
        }
    }

    // ---------- 공통 ----------
    private fun updateStatus(title: String, desc: String, color: Int) {
        statusTitle.text = title
        statusTitle.setTextColor(color)
        statusDesc.text = desc
    }

    private fun showQr() {
        if (LocalHotspot.ssid.isEmpty()) {
            qrView.visibility = View.GONE
            return
        }
        val key = "${LocalHotspot.ssid}|${LocalHotspot.pass}|${LocalHotspot.sec}"
        if (key != lastQr) {
            try {
                qrView.setImageBitmap(QrHelper.wifi(LocalHotspot.ssid, LocalHotspot.pass, LocalHotspot.sec))
                lastQr = key
            } catch (t: Throwable) {
                AppLog.e("ui", "QR 생성 실패", t)
            }
        }
        qrView.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == Activity.RESULT_OK) {
                startVpn(pendingHost)
            } else {
                updateStatus("권한 거부", "VPN 권한이 필요합니다.", Color.RED)
            }
        }
    }

    private fun runTest(host: String) {
        if (host.isEmpty()) return
        Toast.makeText(this, "테스트 중...", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = "대상: $host\nTCP: ${Socks5Test.tcp(host, ServerService.PORT)}\nUDP: ${Socks5Test.udp(host, ServerService.PORT)}"
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("진단 결과").setMessage(msg).setPositiveButton("확인", null).show()
            }
        }.start()
    }

    private fun refresh() {
        if (ServerService.running) {
            btnServer.text = "서버 중지"
            btnServer.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            btnServer.text = "서버 시작\n(데이터 폰)"
            btnServer.setBackgroundColor(Color.parseColor("#1565C0"))
        }

        if (isClientMode) {
            btnClient.text = "연결 해제"
            btnClient.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            btnClient.text = "수동 연결\n(IP 입력)"
            btnClient.setBackgroundColor(Color.parseColor("#2E7D32"))
        }
    }
}
