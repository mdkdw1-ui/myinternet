package com.myinternet.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        
        var host = intent?.getStringExtra("host")
        val mode = intent?.getStringExtra("mode") ?: "udp"  // ★ udp 또는 tcp
        
        // ★ Always-on VPN으로 시작된 경우 저장된 host 또는 자동 탐지
        if (host.isNullOrBlank()) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            host = prefs.getString(KEY_HOST, null)
        }
        
        if (host.isNullOrBlank()) {
            // 저장된 host도 없으면 Wi-Fi 게이트웨이에서 자동 탐지 (S24+ 핫스팟 IP)
            host = detectGatewayHost()
            if (host.isNullOrBlank()) {
                AppLog.e("vpn", "Always-on: host 없음, 서비스 종료")
                stopSelf()
                return START_NOT_STICKY
            }
            AppLog.i("vpn", "Always-on: 자동 탐지된 host=$host")
        } else {
            // host가 있으면 저장 (다음 Always-on 시 사용)
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_HOST, host).apply()
        }
        
        createNotificationChannel()
        val notification = Notification.Builder(this, "vpn_channel")
            .setContentTitle("MyInternet VPN 실행 중")
            .setContentText("서버 $host 로 연결 중")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
            
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(2, notification)
        }

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

        startTunnel(host, mode)
        return START_STICKY
    }
    
    // ★ Wi-Fi 게이트웨이 자동 탐지 (Always-on VPN용)
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
                        AppLog.i("vpn", "자동 탐지: 게이트웨이 = ${g.hostAddress}")
                        return g.hostAddress
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.e("vpn", "게이트웨이 탐지 실패", t)
        }
        return null
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("vpn_channel", "VPN 상태", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Synchronized
    private fun startTunnel(host: String, mode: String) {
        stopTunnel()
        try {
            System.setProperty("java.io.tmpdir", filesDir.absolutePath)
            Class.forName("hev.htproxy.TProxyService")
            
            val builder = Builder()
                .setSession("MyInternet")
                .setMtu(1280)
                .addAddress("198.18.0.1", 32)
                .addDnsServer("198.18.0.2")
                .addDisallowedApplication(packageName)
            
            // 10, 172.16-31, 192.168 제외한 공인 IP만 VPN 라우팅
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
                } else {
                    builder.addRoute("$i.0.0.0", 8)
                }
            }
            
            val fd = builder.establish()
            if (fd == null) {
                AppLog.e("vpn", "establish() null - 권한 없음 또는 다른 VPN 점유")
                stopSelf()
                return
            }
            pfd = fd

            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                           else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "myinternet").apply { setReferenceCounted(false); acquire() }
            } catch (_: Throwable) {}

            val logFile = File(filesDir, "hev.log")
            val cfg = File(filesDir, "hev.yml")
            try { cfg.writeText(
                """
                tunnel:
                  mtu: 1280
                  ipv4: 198.18.0.1
                socks5:
                  address: $host
                  port: ${ServerService.PORT}
                  udp: '${if (mode == "tcp") "tcp" else "udp"}'
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
            ) } catch (e: Throwable) {
                AppLog.e("vpn", "hev.yml 쓰기 실패", e)
                stopSelf()
                return
            }
            
            val r = TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)
            if (!r) {
                AppLog.e("vpn", "TProxyStartService 실패")
                try { pfd?.close() } catch (_: Exception) {}
                pfd = null
                stopSelf()
                return
            }
            started = true
            AppLog.i("vpn", "터널 시작 성공. host=$host")
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
                    if (s.size >= 4) {
                        AppLog.i("hev", "통계 tx=${s[0]}pkt/${s[1]}B rx=${s[2]}pkt/${s[3]}B")
                    }
                }
            } catch (_: InterruptedException) {} catch (t: Throwable) {}
        }
    }

    @Synchronized
    private fun stopTunnel() {
        statsThread?.interrupt()
        statsThread = null
        if (started) {
            started = false
            try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
            try { Thread.sleep(300) } catch (_: Exception) {}
        }
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onRevoke() {
        stopTunnel()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }
}
