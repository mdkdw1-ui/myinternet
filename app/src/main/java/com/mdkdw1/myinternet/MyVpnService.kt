package com.mdkdw1.myinternet

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

class MyVpnService : VpnService() {
    companion object {
        const val CHANNEL_ID = "vpn_service"
        const val NOTIFICATION_ID = 1002
        const val ACTION_START = "VPN_START"
        const val ACTION_STOP = "VPN_STOP"

        @Volatile var isRunning = false
        @Volatile var lastError: String? = null
        @Volatile var instance: MyVpnService? = null
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "MyInternet VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> { stopVpn(); stopSelf() }
        }
        return START_STICKY
    }

    private fun startVpn() {
        startForeground(NOTIFICATION_ID, buildNotification("VPN 연결 준비 중..."))
        try {
            startVpnInternal()
        } catch (e: Throwable) {
            lastError = "VPN 시작 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e("MyVpn", "startVpn error", e)
            try { stopVpn() } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun startVpnInternal() {
        if (isRunning) return
        lastError = null

        if (!HevTunnelManager.isLibLoaded()) {
            lastError = "hev 라이브러리 미탑재"
            stopSelf(); return
        }

        val s = SettingsManager(this)
        val host = s.srvHost; val port = s.srvPort
        val user = s.srvUser; val pass = s.srvPass
        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            lastError = "서버 정보 없음"; stopSelf(); return
        }

        val underlying = LocalHotspotManager.hotspotNetwork
        if (underlying == null) {
            lastError = "핫스팟 네트워크 미준비"
            stopSelf(); return
        }

        try {
            val builder = Builder()
            builder.setSession("MyInternet")
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0)
            builder.addAddress("fd00::1", 128)

            // 서버 IP 우회 라우트
            try {
                val serverIp = java.net.InetAddress.getByName(host).hostAddress
                if (serverIp != null && !serverIp.contains(":")) {
                    builder.addRoute(serverIp, 32)
                    Log.i("MyVpn", "bypass route: $serverIp/32")
                }
            } catch (_: Exception) {}

            // 내 앱 소켓은 VPN 안 탐
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            builder.setUnderlyingNetworks(arrayOf(underlying))
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(1200)  // QUIC(1250B) outer overhead 포함 MTU 안전화
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            val pfd = builder.establish() ?: run { lastError = "TUN 생성 실패"; stopSelf(); return }
            tun = pfd

            Log.d("MyVpn", "Hev 시작: host=$host port=$port fd=${pfd.fd}")
            val started = HevTunnelManager.start(pfd.fd, host, port, user, pass, cacheDir)
            if (!started) {
                lastError = HevTunnelManager.lastError ?: "hev 시작 실패"
                android.widget.Toast.makeText(this, "VPN 실패: $lastError", android.widget.Toast.LENGTH_LONG).show()
                try { pfd.close() } catch (_: Exception) {}
                tun = null; isRunning = false
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(); return
            }
            isRunning = true
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification("VPN 연결됨 · $host:$port"))
            Log.i("MyVpn", "VPN running (hev); underlying=$underlying")
        } catch (e: Exception) {
            lastError = e.message
            try { tun?.close() } catch (_: Exception) {}
            tun = null; isRunning = false
            stopSelf()
        }
    }

    private fun stopVpn() {
        HevTunnelManager.stop()
        try { tun?.close() } catch (_: Exception) {}
        tun = null; isRunning = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() { stopVpn(); instance = null; super.onDestroy() }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, MyVpnService::class.java).apply { action = ACTION_STOP }
        val pi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setContentTitle("MyInternet VPN").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download_done).setOngoing(true)
        if (Build.VERSION.SDK_INT >= 26) b.addAction(Notification.Action.Builder(null, "중지", pi).build())
        else @Suppress("DEPRECATION") b.addAction(android.R.drawable.ic_delete, "중지", pi)
        return b.build()
    }
}
