package com.myinternet.share

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.VpnService
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
    private lateinit var btnClientUdp: Button
    private lateinit var btnClientTcp: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnScan: Button
    private lateinit var btnExitApp: Button
    private lateinit var qrView: ImageView
    private lateinit var statsText: TextView

    private var lastQr = ""
    private var isConnecting = false
    private var selectedMode = "udp"
    private var btnWg: Button? = null
    private var wgConfEt: EditText? = null
    private var btnWgStop: Button? = null
    private var pendingHost = ""
    private var pendingNetwork: android.net.Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    private var lastTx = 0L
    private var lastRx = 0L
    private val uiHandler = Handler(Looper.getMainLooper())

    private val timeoutRunnable = Runnable {
        if (isConnecting) {
            isConnecting = false
            updateStatus("연결 시간 초과", "다시 시도해 주세요", Color.RED)
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val prep = VpnService.prepare(this)
            if (prep != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(prep, 999)
            }
        } catch (_: Throwable) {}

        requestNecessaryPermissions()
        buildPremiumUI()
        startStatsUpdater()
    }

    override fun onResume() {
        super.onResume()

        if (!VpnClientService.running) {
            val detected = NetUtil.serverHost(this)
            if (hostEt.text.toString().isBlank() ||
                hostEt.text.toString() == NetUtil.FALLBACK
            ) {
                hostEt.setText(detected)
            }
        }
        refresh()
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
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        root.addView(TextView(this).apply {
            text = "MyInternet"
            textSize = 26f
            setTextColor(Color.parseColor("#0D47A1"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
            elevation = 8f
        }

        statusTitle = TextView(this).apply {
            text = "대기 중"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
        }

        statusDesc = TextView(this).apply {
            text = "아래 버튼을 눌러 시작하세요"
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }

        statsText = TextView(this).apply {
            text = "속도: - | 데이터: -"
            textSize = 13f
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
            setTypeface(Typeface.MONOSPACE)
        }

        statusCard.addView(statusTitle)
        statusCard.addView(statusDesc)
        statusCard.addView(statsText)

        root.addView(
            statusCard,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, (16 * d).toInt(), 0, (16 * d).toInt())
            }
        )

        btnServer = Button(this).apply {
            text = "서버 시작 (데이터 폰 / S24+)"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener { startServerMode() }
        }

        root.addView(
            btnServer,
            LinearLayout.LayoutParams(-1, (56 * d).toInt()).apply {
                setMargins(4, 0, 4, (12 * d).toInt())
            }
        )

        hostEt = EditText(this).apply {
            hint = "서버 IP 주소 (예: 192.168.43.1)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(NetUtil.serverHost(this@MainActivity))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(16, (12 * d).toInt(), 16, (12 * d).toInt())
        }

        root.addView(
            hostEt,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(4, 0, 4, (10 * d).toInt())
            }
        )

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnClientUdp = Button(this).apply {
            text = "🎮 UDP 연결\n(게임용)"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener { connectClientMode("udp") }
        }

        btnClientTcp = Button(this).apply {
            text = "🛒 TCP 연결\n(롯데·H몰·웹용)"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#00838F"))
            setTextColor(Color.WHITE)
            setOnClickListener { connectClientMode("tcp") }
        }

        modeRow.addView(
            btnClientUdp,
            LinearLayout.LayoutParams(0, (68 * d).toInt(), 1f).apply {
                setMargins(4, 0, 6, 0)
            }
        )

        modeRow.addView(
            btnClientTcp,
            LinearLayout.LayoutParams(0, (68 * d).toInt(), 1f).apply {
                setMargins(6, 0, 4, 0)
            }
        )

        root.addView(modeRow)

        btnDisconnect = Button(this).apply {
            text = "클라이언트 연결 해제 (OFF)"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)

            setOnClickListener {
                if (VpnClientService.running || isConnecting) {
                    stopClientSafely()
                } else {
                    Toast.makeText(
                        context,
                        "현재 연결된 VPN이 없습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        root.addView(
            btnDisconnect,
            LinearLayout.LayoutParams(-1, (54 * d).toInt()).apply {
                setMargins(4, (10 * d).toInt(), 4, 0)
            }
        )

        btnScan = Button(this).apply {
            text = "QR 코드 스캔하여 Wi-Fi 연결"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#F57C00"))
            setTextColor(Color.WHITE)
            setOnClickListener { startQrScanLegacy() }
        }

        root.addView(
            btnScan,
            LinearLayout.LayoutParams(-1, (48 * d).toInt()).apply {
                setMargins(4, (10 * d).toInt(), 4, 0)
            }
        )

        qrView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        root.addView(
            qrView,
            LinearLayout.LayoutParams(
                (190 * d).toInt(),
                (190 * d).toInt()
            ).apply {
                gravity = Gravity.CENTER
                setMargins(0, 16, 0, 16)
            }
        )

        val wgLabel = TextView(this).apply {
            text = "WireGuard (S24+ IP 변경용 client.conf)"
            textSize = 13f
            setTextColor(Color.parseColor("#4A148C"))
            setPadding(4, (18 * d).toInt(), 0, 4)
        }

        wgConfEt = EditText(this).apply {
            hint = "[Interface]\nPrivateKey = ...\nAddress = ...\n\n[Peer]\nPublicKey = ...\nEndpoint = ...\nAllowedIPs = ..."
            textSize = 11f
            gravity = Gravity.START or Gravity.TOP
            setSingleLine(false)
            setLines(5)
            setText(NetUtil.loadWgConf(this@MainActivity))
            setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
        }

        val wgFileBtn = Button(this).apply {
            text = "client.conf 파일 불러오기"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#455A64"))
            setTextColor(Color.WHITE)

            setOnClickListener {
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }

                    @Suppress("DEPRECATION")
                    startActivityForResult(i, 102)
                } catch (t: Throwable) {
                    Toast.makeText(
                        this@MainActivity,
                        "파일 선택기 오류: " + t.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val wgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnWg = Button(this).apply {
            text = "WireGuard ON"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#6A1B9A"))
            setTextColor(Color.WHITE)
            setOnClickListener { startWireGuard() }
        }

        btnWgStop = Button(this).apply {
            text = "WireGuard OFF"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#4A148C"))
            setTextColor(Color.WHITE)
            setOnClickListener { stopWireGuard() }
        }

        wgRow.addView(
            btnWg,
            LinearLayout.LayoutParams(0, (50 * d).toInt(), 1f).apply {
                setMargins(4, 0, 4, 0)
            }
        )

        wgRow.addView(
            btnWgStop,
            LinearLayout.LayoutParams(0, (50 * d).toInt(), 1f).apply {
                setMargins(4, 0, 4, 0)
            }
        )

        root.addView(wgLabel)

        root.addView(
            wgFileBtn,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(4, 0, 4, 6)
            }
        )

        root.addView(
            wgConfEt,
            LinearLayout.LayoutParams(-1, (120 * d).toInt()).apply {
                setMargins(4, 0, 4, 8)
            }
        )

        root.addView(wgRow)

        btnExitApp = Button(this).apply {
            text = "✕ 완전 종료 (모든 서버·VPN 끄고 앱 닫기)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#263238"))
            setTextColor(Color.WHITE)
            setOnClickListener { exitApplicationCompletely() }
        }

        root.addView(
            btnExitApp,
            LinearLayout.LayoutParams(-1, (52 * d).toInt()).apply {
                setMargins(4, (18 * d).toInt(), 4, 4)
            }
        )

        val utilRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun utilBtn(t: String, f: () -> Unit) =
            Button(this).apply {
                text = t
                textSize = 12f
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(Color.parseColor("#1565C0"))
                setOnClickListener { f() }
            }

        utilRow.addView(
            utilBtn("로그") {
                startActivity(Intent(this, LogActivity::class.java))
            }
        )

        utilRow.addView(
            utilBtn("VPN 설정") {
                fallbackToSystemVpnSettings()
            }
        )

        utilRow.addView(
            utilBtn("와이파이") {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        )

        utilRow.addView(
            utilBtn("연결 테스트") {
                runTest(hostEt.text.toString().trim())
            }
        )

        root.addView(
            utilRow,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 8, 0, 0)
            }
        )

        setContentView(ScrollView(this).apply {
            addView(root)
        })

        refresh()
    }

    private fun exitApplicationCompletely() {
        Toast.makeText(
            this,
            "모든 연결을 종료하고 앱을 닫습니다...",
            Toast.LENGTH_SHORT
        ).show()

        try {
            LocalHotspot.stop()

            startService(
                Intent(this, ServerService::class.java).setAction("stop")
            )
            stopService(Intent(this, ServerService::class.java))

            stopClientSafely(silent = true)

            startService(
                Intent(this, WireGuardService::class.java).setAction("stop")
            )
            stopService(Intent(this, WireGuardService::class.java))
        } catch (_: Throwable) {}

        uiHandler.postDelayed({
            try {
                finishAndRemoveTask()
            } catch (_: Throwable) {
                finish()
            }

            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500)
    }

    private fun startStatsUpdater() {
        uiHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    refresh()

                    if (VpnClientService.running) {
                        val stats = TProxyService.TProxyGetStats()

                        if (stats.size >= 4) {
                            val tx = stats[1]
                            val rx = stats[3]

                            val dTx = (tx - lastTx).coerceAtLeast(0L)
                            val dRx = (rx - lastRx).coerceAtLeast(0L)

                            fun formatBytes(b: Long): String {
                                if (b < 1024) return "${b}B"

                                if (b < 1024 * 1024) {
                                    return String.format("%.1fKB", b / 1024.0)
                                }

                                return String.format(
                                    "%.1fMB",
                                    b / (1024.0 * 1024.0)
                                )
                            }

                            statsText.text =
                                "▼ ${formatBytes(dRx)}/s ▲ ${formatBytes(dTx)}/s | 누적: ${formatBytes(tx + rx)}"

                            lastTx = tx
                            lastRx = rx
                        }
                    } else if (ServerService.running) {
                        val wgInfo =
                            if (WireGuardService.running) " | WG 활성" else ""

                        statsText.text =
                            "접속 중인 클라이언트: ${ServerService.activeClientCount}대$wgInfo"
                    } else if (WireGuardService.running) {
                        statsText.text = "WireGuard VPN 작동 중"
                    } else {
                        statsText.text = "속도: - | 데이터: -"
                    }
                } catch (_: Throwable) {}

                uiHandler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun connectClientMode(mode: String) {
        if (WireGuardService.running) {
            Toast.makeText(
                this,
                "WireGuard를 먼저 끄세요.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val host = hostEt.text.toString().trim().ifEmpty {
            NetUtil.serverHost(this).also {
                hostEt.setText(it)
            }
        }

        if (host.isEmpty()) {
            Toast.makeText(
                this,
                "서버 IP를 입력하세요",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (VpnClientService.running) {
            updateStatus(
                "모드 전환 중...",
                "기존 연결을 안전하게 해제하는 중",
                Color.parseColor("#F57C00")
            )

            stopClientSafely(silent = true)

            uiHandler.postDelayed({
                selectedMode = mode
                pendingNetwork = null
                requestVpnPermissionAndStart(host)
            }, 700)

            return
        }

        selectedMode = mode
        pendingNetwork = null
        requestVpnPermissionAndStart(host)
    }

    private fun startQrScanLegacy() {
        if (VpnClientService.running) {
            Toast.makeText(
                this,
                "VPN 연결 중에는 QR 스캔 불가. 먼저 연결 해제하세요.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

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
                    connectClientMode("udp")
                    return
                }

                updateStatus("QR 오류", "Wi-Fi QR이 아닙니다.", Color.RED)
                return
            }

            val ssid = Regex("S:([^;]+)")
                .find(qrText)
                ?.groupValues
                ?.get(1)
                ?.replace("\\\\", "\\")
                ?: return

            val pass = Regex("P:([^;]+)")
                .find(qrText)
                ?.groupValues
                ?.get(1)
                ?.replace("\\\\", "\\")
                ?: ""

            updateStatus(
                "와이파이 수동 연결 필요",
                "설정에서 '$ssid' 연결 후 돌아와 UDP 또는 TCP를 누르세요.",
                Color.parseColor("#F57C00")
            )

            Toast.makeText(
                this,
                "비밀번호가 클립보드에 복사되었습니다.",
                Toast.LENGTH_LONG
            ).show()

            val cm =
                getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager

            cm.setPrimaryClip(
                android.content.ClipData.newPlainText("wifi", pass)
            )

            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (t: Throwable) {
            updateStatus(
                "QR 오류",
                t.message ?: "해석 실패",
                Color.RED
            )
        }
    }

    private fun startServerMode() {
        if (ServerService.running) {
            LocalHotspot.stop()

            startService(
                Intent(this, ServerService::class.java).setAction("stop")
            )
            stopService(Intent(this, ServerService::class.java))

            qrView.visibility = View.GONE

            updateStatus(
                "대기 중",
                "서버 중지됨",
                Color.parseColor("#333333")
            )
        } else {
            updateStatus(
                "서버 시작 중...",
                "핫스팟 활성화 중",
                Color.parseColor("#F57C00")
            )

            LocalHotspot.start(
                this,
                onStoppedUi = {
                    runOnUiThread { refresh() }
                }
            ) { ok ->
                if (ok) {
                    try {
                        val srvIntent = Intent(this, ServerService::class.java)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(srvIntent)
                        } else {
                            startService(srvIntent)
                        }

                        updateStatus(
                            "서버 실행 중",
                            "SSID: ${LocalHotspot.ssid}\nPW: ${LocalHotspot.pass}",
                            Color.parseColor("#1565C0")
                        )

                        showQr()
                    } catch (t: Throwable) {
                        updateStatus(
                            "오류",
                            t.message ?: "실패",
                            Color.RED
                        )
                    }
                } else {
                    updateStatus(
                        "실패",
                        "핫스팟 불가 (기존 핫스팟이 켜져 있다면 끄고 시도)",
                        Color.RED
                    )
                }

                refresh()
            }
        }

        refresh()
    }

    private fun startWireGuard() {
        val conf = wgConfEt?.text?.toString()?.trim() ?: ""

        if (
            conf.isEmpty() ||
            !conf.contains("[Interface]") ||
            !conf.contains("[Peer]")
        ) {
            Toast.makeText(
                this,
                "client.conf 전체를 붙여넣으세요",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (VpnClientService.running) {
            Toast.makeText(
                this,
                "클라이언트 VPN을 먼저 끄세요",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            NetUtil.saveWgConf(this, conf)

            val f = java.io.File(filesDir, "wg.conf")
            val prep = VpnService.prepare(this)

            if (prep != null) {
                pendingHost = "WG"

                @Suppress("DEPRECATION")
                startActivityForResult(prep, 101)
            } else {
                reallyStartWg(f.absolutePath)
            }
        } catch (t: Throwable) {
            AppLog.e("ui", "WireGuard 시작 준비 실패", t)

            Toast.makeText(
                this,
                "오류: ${t.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun reallyStartWg(confPath: String) {
        try {
            val f = java.io.File(confPath)

            if (!f.exists() || f.length() < 50) {
                Toast.makeText(
                    this,
                    "conf 파일이 비어있습니다",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val wgIntent = Intent(this, WireGuardService::class.java)
                .setAction("start")
                .putExtra("confPath", confPath)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(wgIntent)
            } else {
                startService(wgIntent)
            }

            updateStatus(
                "WireGuard 연결 중...",
                "핸드셰이크 대기",
                Color.parseColor("#F57C00")
            )

            refresh()

            uiHandler.postDelayed({
                if (WireGuardService.running) {
                    updateStatus(
                        "WireGuard 연결됨",
                        "S24+ 외부 IP 변경 적용됨",
                        Color.parseColor("#6A1B9A")
                    )
                } else {
                    updateStatus(
                        "WireGuard 실패",
                        "로그 확인 필요",
                        Color.RED
                    )
                }

                refresh()
            }, 3000)
        } catch (t: Throwable) {
            AppLog.e("ui", "WireGuard 서비스 시작 실패", t)

            Toast.makeText(
                this,
                "서비스 시작 실패: ${t.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopWireGuard() {
        try {
            startService(
                Intent(this, WireGuardService::class.java).setAction("stop")
            )
        } catch (_: Throwable) {}

        updateStatus(
            "대기 중",
            "WireGuard 해제됨",
            Color.parseColor("#333333")
        )

        uiHandler.postDelayed({ refresh() }, 500)
    }

    private fun stopClientSafely(silent: Boolean = false) {
        try {
            isConnecting = false
            uiHandler.removeCallbacks(timeoutRunnable)

            val stopIntent =
                Intent(this, VpnClientService::class.java).setAction("stop")

            startService(stopIntent)
            stopService(Intent(this, VpnClientService::class.java))

            val cm =
                getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

            try {
                cm.bindProcessToNetwork(null)
            } catch (_: Throwable) {}

            wifiCallback?.let {
                try {
                    cm.unregisterNetworkCallback(it)
                } catch (_: Throwable) {}
            }

            wifiCallback = null
            pendingNetwork = null

            getSharedPreferences("ui_state", MODE_PRIVATE)
                .edit()
                .putBoolean("is_client", false)
                .apply()

            lastTx = 0L
            lastRx = 0L

            if (!silent) {
                updateStatus(
                    "대기 중",
                    "VPN 연결이 완전히 해제되었습니다.",
                    Color.parseColor("#455A64")
                )

                Toast.makeText(
                    this,
                    "연결 해제 완료",
                    Toast.LENGTH_SHORT
                ).show()
            }

            uiHandler.postDelayed({ refresh() }, 350)
        } catch (t: Throwable) {
            AppLog.e("ui", "종료 중 오류", t)
        }
    }

    private fun requestVpnPermissionAndStart(host: String) {
        if (isConnecting) return

        isConnecting = true

        updateStatus(
            "연결 준비 중...",
            "${selectedMode.uppercase()} 모드로 연결 시도",
            Color.parseColor("#F57C00")
        )

        refresh()

        uiHandler.removeCallbacks(timeoutRunnable)
        uiHandler.postDelayed(timeoutRunnable, 15000)

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
            isConnecting = false
            uiHandler.removeCallbacks(timeoutRunnable)

            val intent = Intent(this, VpnClientService::class.java)
                .putExtra("host", host)
                .putExtra("mode", selectedMode)

            pendingNetwork?.let {
                intent.putExtra("network", it)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            getSharedPreferences("ui_state", MODE_PRIVATE)
                .edit()
                .putBoolean("is_client", true)
                .apply()

            lastTx = 0L
            lastRx = 0L

            val modeText =
                if (selectedMode == "tcp") {
                    "TCP 모드 (쇼핑/웹용)"
                } else {
                    "UDP 모드 (게임용)"
                }

            updateStatus(
                "클라이언트 연결됨",
                "서버 $host | $modeText",
                Color.parseColor("#2E7D32")
            )

            uiHandler.postDelayed({ refresh() }, 400)
        } catch (t: Throwable) {
            isConnecting = false

            AppLog.e("ui", "startVpn 실패", t)

            updateStatus(
                "연결 오류",
                t.message ?: "VPN 시작 실패",
                Color.RED
            )

            refresh()
        }
    }

    private fun updateStatus(title: String, desc: String, color: Int) {
        val icon = when {
            title.contains("연결됨") || title.contains("실행 중") -> "●"
            title.contains("중") -> "◐"
            title.contains("실패") ||
                title.contains("오류") ||
                title.contains("거부") ||
                title.contains("초과") -> "✕"
            else -> "○"
        }

        statusTitle.text = "$icon $title"
        statusTitle.setTextColor(color)
        statusDesc.text = desc
    }

    private fun showQr() {
        if (LocalHotspot.ssid.isEmpty()) {
            qrView.visibility = View.GONE
            return
        }

        val key =
            "${LocalHotspot.ssid}|${LocalHotspot.pass}|${LocalHotspot.sec}"

        if (key != lastQr) {
            try {
                qrView.setImageBitmap(
                    QrHelper.wifi(
                        LocalHotspot.ssid,
                        LocalHotspot.pass,
                        LocalHotspot.sec
                    )
                )
                lastQr = key
            } catch (_: Throwable) {}
        }

        qrView.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        val result: IntentResult? =
            IntentIntegrator.parseActivityResult(requestCode, resultCode, data)

        if (result != null) {
            if (result.contents == null) {
                updateStatus(
                    "스캔 취소",
                    "QR 스캔이 취소되었습니다.",
                    Color.GRAY
                )
            } else {
                handleWifiQr(result.contents)
            }
        } else if (requestCode == 102) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                try {
                    val text =
                        contentResolver.openInputStream(data.data!!)
                            ?.bufferedReader()
                            ?.use { it.readText() } ?: ""

                    if (text.isNotBlank()) {
                        wgConfEt?.setText(text)
                        NetUtil.saveWgConf(this, text)

                        Toast.makeText(
                            this,
                            "client.conf 로드 (" + text.length + "자)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (t: Throwable) {
                    Toast.makeText(
                        this,
                        "읽기 실패: " + t.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else if (requestCode == 101) {
            if (resultCode == Activity.RESULT_OK) {
                val f = java.io.File(filesDir, "wg.conf")
                reallyStartWg(f.absolutePath)
            } else {
                updateStatus(
                    "권한 거부",
                    "WireGuard VPN 권한 필요",
                    Color.RED
                )
            }
        } else if (requestCode == 999) {
            if (resultCode == Activity.RESULT_OK) {
                AppLog.i("ui", "VPN 권한 사전 승인 완료")
            }
        } else if (requestCode == 100) {
            isConnecting = false
            uiHandler.removeCallbacks(timeoutRunnable)

            if (resultCode == Activity.RESULT_OK) {
                startVpn(pendingHost)
            } else {
                updateStatus(
                    "권한 거부",
                    "VPN 권한 승인이 필요합니다",
                    Color.RED
                )
                refresh()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun runTest(host: String) {
        if (host.isEmpty()) {
            Toast.makeText(
                this,
                "IP를 입력하세요",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        Toast.makeText(
            this,
            "테스트 중...",
            Toast.LENGTH_SHORT
        ).show()

        Thread {
            try {
                val tcpResult =
                    Socks5Test.tcp(this, host, ServerService.PORT)

                val udpResult =
                    Socks5Test.udp(this, host, ServerService.PORT)

                val msg =
                    "대상: $host\nTCP: $tcpResult\nUDP: $udpResult"

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this)
                            .setTitle("결과")
                            .setMessage(msg)
                            .setPositiveButton("확인", null)
                            .show()
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this)
                            .setTitle("테스트 실패")
                            .setMessage("예외 발생: ${e.message}")
                            .setPositiveButton("확인", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun refresh() {
        val vpnOn = VpnClientService.running
        val wgOn = WireGuardService.running
        val srvOn = ServerService.running
        val activeMode = VpnClientService.currentActiveMode

        if (srvOn) {
            btnServer.text = "서버 중지 (실행 중)"
            btnServer.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            btnServer.text = "서버 시작 (데이터 폰 / S24+)"
            btnServer.setBackgroundColor(Color.parseColor("#1565C0"))
        }

        btnWg?.isEnabled = !vpnOn && !wgOn
        btnWgStop?.isEnabled = wgOn
        btnClientUdp.isEnabled = !wgOn && !isConnecting
        btnClientTcp.isEnabled = !wgOn && !isConnecting
        btnDisconnect.isEnabled = vpnOn || isConnecting

        if (vpnOn) {
            if (activeMode == "tcp") {
                btnClientUdp.text = "🎮 UDP로 전환\n(게임용)"
                btnClientUdp.setBackgroundColor(Color.parseColor("#546E7A"))

                btnClientTcp.text = "● TCP 연결됨\n(쇼핑/웹)"
                btnClientTcp.setBackgroundColor(Color.parseColor("#00838F"))
            } else {
                btnClientUdp.text = "● UDP 연결됨\n(게임)"
                btnClientUdp.setBackgroundColor(Color.parseColor("#2E7D32"))

                btnClientTcp.text = "🛒 TCP로 전환\n(롯데·H몰·웹)"
                btnClientTcp.setBackgroundColor(Color.parseColor("#546E7A"))
            }
        } else {
            btnClientUdp.text = "🎮 UDP 연결\n(게임용)"
            btnClientUdp.setBackgroundColor(Color.parseColor("#2E7D32"))

            btnClientTcp.text = "🛒 TCP 연결\n(롯데·H몰·웹용)"
            btnClientTcp.setBackgroundColor(Color.parseColor("#00838F"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
    }

    private fun fallbackToSystemVpnSettings() {
        Toast.makeText(
            this,
            "시스템 VPN 설정 화면으로 이동합니다.",
            Toast.LENGTH_SHORT
        ).show()

        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}