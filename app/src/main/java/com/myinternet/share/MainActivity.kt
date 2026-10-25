package com.myinternet.share

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : Activity() {
    private lateinit var hostEt: EditText
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
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
            startForegroundService(Intent(this, ServerService::class.java))
            refresh()
        }
        btn("서버 중지") {
            stopService(Intent(this, ServerService::class.java))
        }
        btn("핫스팟/무선 설정 열기") {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }

        label("클라이언트 (유심 없는 폰)")
        hostEt = EditText(this).apply {
            hint = "서버 IP"
            setText(gateway())
        }
        root.addView(hostEt)
        btn("연결") {
            val prep = VpnService.prepare(this)
            if (prep != null) startActivityForResult(prep, 100) else startVpn()
        }
        btn("연결 해제") {
            startService(Intent(this, VpnClientService::class.java).setAction("stop"))
        }

        info = label("")
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        startService(
            Intent(this, VpnClientService::class.java)
                .putExtra("host", hostEt.text.toString().trim())
        )
    }

    private fun refresh() {
        if (::info.isInitialized) {
            info.text = "내 IP 목록 (서버 포트 ${ServerService.PORT})\n" + ips()
            info.textSize = 14f
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
