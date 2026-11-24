package com.myinternet.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import java.net.Inet4Address
import kotlin.concurrent.thread

class VpnClientService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var statsThread: Thread? = null
    private var started = false
    private val PREFS_NAME = "vpn_prefs"
    private val KEY_HOST = "last_host"
    private val KEY_MODE = "last_mode"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            AppLog.i("vpn", "수동 중지 요청 수신")
            stopTunnel()
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var host = intent?.getStringExtra("host")
        val mode = intent?.getStringExtra("mode") ?: prefs.getString(KEY_MODE, "udp") ?: "udp"

        if (host.isNullOrBlank()) host = prefs.getString(KEY_HOST, null)
        if (host.isNullOrBlank()) {
            host = detectGatewayHost()
            if (host.isNullOrBlank()) {
                AppLog.e("vpn", "host 없음, 서비스 종료")
                stopSelf()
                return START_NOT_STICKY
            }
            AppLog.i("vpn", "자동 탐지된 host=$host")
        }

        prefs.edit().putString(KEY_HOST, host).putString(KEY_MODE, mode).apply()

        createNotificationChannel()
        val modeLabel = if (mode == "tcp") "TCP (웹/쇼핑)" else "UDP (게임)"
        val notification = Notification.Builder(this, "vpn_channel")
            .setContentTitle("MyInternet VPN ($modeLabel)")
            .setContentText("서버 $host 연결 중")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        startForeground(2, notification)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val network = intent?.getParcelableExtra<Network>("network")
            if (network != null) {
                try {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.bindProcessToNetwork(network)
                } catch (_: Throwable) {}
            }
        }

        if (!started || pfd == null || currentActiveMode != mode) {
            startTunnel(host, mode)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i("vpn", "onTaskRemoved - VPN 터널 백그라운드 유지")
        if (running) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val restartIntent = Intent(applicationContext, VpnClientService::class.java).apply {
                putExtra("host", prefs.getString(KEY_HOST, ""))
                putExtra("mode", prefs.getString(KEY_MODE, "udp"))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restartIntent)
            else startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun detectGatewayHost(): String? {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(n) ?: continue
                for (r in lp.routes) {
                    val g = r.gateway
                    if (r.isDefaultRoute && g is Inet4Address && !g.isAnyLocalAddress) {
                        return g.hostAddress
                    }
                }
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address) {
                        val b = a.address
                        return "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}.1"
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.e("vpn", "게이트웨이 탐지 실패", t)
        }
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("vpn_channel", "VPN 상태", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    @Synchronized
    private fun startTunnel(host: String, mode: String) {
        stopTunnel()
        try {
            System.setProperty("java.io.tmpdir", filesDir.absolutePath)
            Class.forName("hev.htproxy.TProxyService")

            val builder = Builder()
                .setSession("MyInternet-${mode.uppercase()}")
                .setMtu(1280)
                .addAddress("198.18.0.1", 32)
                .addDnsServer("198.18.0.2")
                .addDisallowedApplication(packageName)

            for (i in 1..223) {
                if (i == 10 || i == 127) continue
                if (i == 172) {
                    for (j in 0..255) {
                        if (j in 16..31) continue
                        builder.addRoute("172.$j.0.0", 16)
                    }
                } else if (i == 192) {
                    for (j in 0..255) {
                        if (j == 168) continue
                        builder.addRoute("192.$j.0.0", 16)
                    }
                } else builder.addRoute("$i.0.0.0", 8)
            }

            val fd = builder.establish()
            if (fd == null) {
                AppLog.e("vpn", "establish() null - 권한 없음 또는 다른 VPN 점유 중")
                running = false
                stopSelf()
                return
            }
            pfd = fd

            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val lockMode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(lockMode, "myinternet").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Throwable) {}

            val logFile = File(filesDir, "hev.log")
            val cfg = File(filesDir, "hev.yml")
            val udpMode = if (mode == "tcp") "tcp" else "udp"

            try {
                cfg.writeText(
                    """
                    tunnel:
                      mtu: 1280
                      ipv4: 198.18.0.1
                    socks5:
                      address: $host
                      port: ${ServerService.PORT}
                      udp: '$udpMode'
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
                      read-write-timeout: 300000
                    """.trimIndent() + "\n"
                )
            } catch (e: Throwable) {
                AppLog.e("vpn", "hev.yml 쓰기 실패", e)
                stopTunnel()
                stopSelf()
                return
            }

            val r = TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)
            if (!r) {
                AppLog.e("vpn", "TProxyStartService 실패")
                stopTunnel()
                stopSelf()
                return
            }
            started = true
            running = true
            currentActiveMode = mode
            AppLog.i("vpn", "터널 시작 성공. host=$host mode=$mode")
            startStats()
        } catch (t: Throwable) {
            AppLog.e("vpn", "터널 시작 실패", t)
            stopTunnel()
            stopSelf()
        }
    }

    private fun startStats() {
        statsThread = thread(isDaemon = true) {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(5000)
                    val s = TProxyService.TProxyGetStats()
                    if (s.size >= 4) AppLog.i("hev", "통계 tx=${s[0]}pkt/${s[1]}B rx=${s[2]}pkt/${s[3]}B")
                }
            } catch (_: InterruptedException) {} catch (_: Throwable) {}
        }
    }

    @Synchronized
    private fun stopTunnel() {
        statsThread?.interrupt()
        statsThread = null
        if (started || TProxyService.TProxyIsRunning()) {
            started = false
            try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
            try { Thread.sleep(250) } catch (_: Exception) {}
        }
        running = false
        currentActiveMode = ""
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        AppLog.i("vpn", "터널 완전 종료됨")
    }

    override fun onRevoke() {
        AppLog.i("vpn", "시스템에 의해 VPN 권한 회수됨(onRevoke)")
        stopTunnel()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        @Volatile var currentActiveMode = ""
    }
}
