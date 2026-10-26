package com.myinternet.share

import android.content.Intent
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
