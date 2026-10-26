package com.myinternet.share

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.graphics.Bitmap
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : Activity() {
    private lateinit var hostEt: EditText
    private lateinit var info: TextView
    private lateinit var qrView: ImageView
    private var lastQr = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("ui", "MainActivity onCreate")
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        requestPermissions(perms.toTypedArray(), 1)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 18f
            setPadding(0, pad, 0, 0)
        }.also { root.addView(it) }

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { f() }
        }.also { root.addView(it) }

        label("서버 (데이터 있는 폰)")
        btn("서버 시작") {
            try {
                startForegroundService(Intent(this, ServerService::class.java))
            } catch (t: Throwable) {
                AppLog.e("ui", "서버 시작 요청 실패", t)
            }
            root.postDelayed({ refresh() }, 800)
        }
        btn("서버 중지") {
            stopService(Intent(this, ServerService::class.java))
            root.postDelayed({ refresh() }, 500)
        }
        btn("서버 자가 테스트 (이 폰)") { runTest("127.0.0.1") }
        btn("핫스팟/무선 설정 열기") {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
        btn("로컬 핫스팟 + 서버 시작 (공유데이터 불필요)") {
            LocalHotspot.start(this) { ok ->
                if (ok) {
                    try {
                        startForegroundService(Intent(this, ServerService::class.java))
                    } catch (t: Throwable) {
                        AppLog.e("ui", "서버 시작 요청 실패", t)
                    }
                }
                root.postDelayed({ refresh() }, 800)
            }
        }
        btn("로컬 핫스팟 + 서버 중지") {
            LocalHotspot.stop()
            stopService(Intent(this, ServerService::class.java))
            root.postDelayed({ refresh() }, 500)
        }
        btn("핫스팟 이름/비밀번호 복사") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hotspot", "${LocalHotspot.ssid}\n${LocalHotspot.pass}"))
            Toast.makeText(this, "복사했습니다", Toast.LENGTH_SHORT).show()
        }
        qrView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val qs = (280 * resources.displayMetrics.density).toInt()
        root.addView(qrView, LinearLayout.LayoutParams(qs, qs))

        label("클라이언트 (유심 없는 폰)")
        hostEt = EditText(this).apply {
            hint = "서버 IP"
            setText(NetUtil.serverHost(this@MainActivity))
        }
        root.addView(hostEt)
        btn("연결") {
            val prep = VpnService.prepare(this)
            if (prep != null) startActivityForResult(prep, 100) else startVpn()
        }
        btn("연결 해제") {
            startService(Intent(this, VpnClientService::class.java).setAction("stop"))
        }
        btn("서버 연결 테스트 (입력한 IP)") { runTest(hostEt.text.toString().trim()) }

        label("진단")
        btn("로그 보기 / 복사") { startActivity(Intent(this, LogActivity::class.java)) }

        info = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, 0) }
        root.addView(info)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::hostEt.isInitialized && (hostEt.text.isNullOrBlank() || hostEt.text.toString() == NetUtil.FALLBACK)) hostEt.setText(NetUtil.serverHost(this@MainActivity))
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        AppLog.i("ui", "VPN 권한 결과 code=$resultCode")
        if (requestCode == 100 && resultCode == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        val host = hostEt.text.toString().trim()
        AppLog.i("ui", "VPN 연결 요청 host=$host")
        try {
            startService(Intent(this, VpnClientService::class.java).putExtra("host", host))
        } catch (t: Throwable) {
            AppLog.e("ui", "VPN 서비스 시작 실패", t)
        }
    }

    private fun runTest(host: String) {
        Toast.makeText(this, "테스트 중... (최대 15초)", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = "대상: $host:${ServerService.PORT}\nTCP: " +
                Socks5Test.tcp(host, ServerService.PORT) +
                "\nUDP(DNS): " + Socks5Test.udp(host, ServerService.PORT)
            AppLog.i("test", msg)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("테스트 결과")
                    .setMessage(msg)
                    .setPositiveButton("확인", null)
                    .show()
            }
        }.start()
    }

    private fun updateQr() {
        if (!::qrView.isInitialized) return
        if (LocalHotspot.ssid.isEmpty()) {
            qrView.visibility = View.GONE
            lastQr = ""
            return
        }
        val key = "${LocalHotspot.ssid}|${LocalHotspot.pass}|${LocalHotspot.sec}"
        if (key != lastQr) {
            try {
                val bmp: Bitmap = QrHelper.wifi(LocalHotspot.ssid, LocalHotspot.pass, LocalHotspot.sec)
                qrView.setImageBitmap(bmp)
                lastQr = key
            } catch (t: Throwable) {
                AppLog.e("ui", "QR 생성 실패", t)
            }
        }
        qrView.visibility = View.VISIBLE
    }

    private fun refresh() {
        updateQr()
        if (::info.isInitialized) {
            val hs = if (LocalHotspot.ssid.isNotEmpty())
                "\n로컬 핫스팟: ${LocalHotspot.ssid}\n비밀번호: ${LocalHotspot.pass}"
            else "\n로컬 핫스팟: ${LocalHotspot.state}"
            info.text = "서버 상태: " + (if (ServerService.running) "실행 중" else "중지") +
                " (포트 ${ServerService.PORT})" + hs + "\n내 IP 목록\n" + ips()
        }
    }

    private fun gateway(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val g = wm.dhcpInfo.gateway
            if (g == 0) "192.168.43.1"
            else "${g and 255}.${(g shr 8) and 255}.${(g shr 16) and 255}.${(g shr 24) and 255}"
        } catch (e: Exception) {
            "192.168.43.1"
        }
    }

    private fun ips(): String = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { n ->
            Collections.list(n.inetAddresses)
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .map { "${n.name}  ${it.hostAddress}" }
        }.joinToString("\n")
    } catch (e: Exception) {
        ""
    }
}
