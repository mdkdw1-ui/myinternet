package com.myinternet.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
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
        if (intent?.action == "stop") {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra("host") ?: return START_NOT_STICKY
        
        // ★ 버그 수정 3: Foreground Service 등록 (안드로이드가 죽이지 못하게 함)
        createNotificationChannel()
        val notification = Notification.Builder(this, "vpn_channel")
            .setContentTitle("MyInternet VPN 연결됨")
            .setContentText("서버 $host 로 우회 중")
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
            val network = intent.getParcelableExtra<Network>("network")
            if (network != null) {
                try {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.bindProcessToNetwork(network)
                } catch (_: Throwable) {}
            }
        }

        startTunnel(host)
        return START_STICKY // ★ OS에 의해 죽으면 자동 재시작
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("vpn_channel", "VPN 상태", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Synchronized
    private fun startTunnel(host: String) {
        stopTunnel()
        try {
            Class.forName("hev.htproxy.TProxyService")
            val builder = Builder()
                .setSession("MyInternet")
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addDnsServer("198.18.0.2")
                .addDisallowedApplication(packageName)
            
            // ★ 핫스팟/로컬 IP 대역을 VPN 라우팅에서 제외 (0.0.0.0/0 대신 사설 IP 제외)
            // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 을 제외한 모든 IP를 VPN으로 라우팅
            val localCidrs = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
            
            // 전체 IP 대역(0.0.0.0/0)을 여러 조각으로 나누어 사설 IP만 제외
            for (i in 0..255) {
                val prefix = "$i.0.0.0/8"
                if (prefix.startsWith("10.") || prefix.startsWith("172.") || prefix.startsWith("192.")) {
                    // 사설 IP 대역은 세밀하게 제외
                    if (prefix.startsWith("172.")) {
                        for (j in 16..31) builder.addRoute("172.$j.0.0/16", 16)
                    } else if (prefix.startsWith("192.")) {
                        for (j in 168..168) builder.addRoute("192.$j.0.0/16", 16)
                    }
                    // 10.x.x.x 는 통째로 제외 (continue)
                } else {
                    builder.addRoute(prefix, 8)
                }
            }
            
            val fd = builder.establish()
            if (fd == null) {
                AppLog.e("vpn", "establish() null (권한 없음)")
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
            ) } catch (e: Throwable) {
                AppLog.e("vpn", "hev.yml 쓰기 실패 (저장공간 부족?)", e)
                stopSelf()
                return
            }
            val r = TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)
            if (!r) {
                AppLog.e("vpn", "TProxyStartService 실패! 터널 종료")
                try { pfd?.close() } catch (_: Exception) {}
                pfd = null
                stopSelf()
                return
            }
            started = true
            AppLog.i("vpn", "TProxyStartService 반환: $r")
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
            // ★ 네이티브 라이브러리가 FD를 안전하게 반환할 시간을 줌 (SIGSEGV 방지)
            try { Thread.sleep(300) } catch (_: Exception) {}
        }
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        // ★ 유령 알림 방지: 서비스가 죽으면 알림창에서도 확실히 제거
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }
}
