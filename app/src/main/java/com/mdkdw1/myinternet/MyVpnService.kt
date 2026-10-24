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
        try { startVpnInternal() }
        catch (e: Throwable) {
            lastError = "VPN 시작 오류: ${e.javaClass.simpleName} - ${e.message}"
            e.printStackTrace()
            try { stopVpn() } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun startVpnInternal() {
        if (isRunning) return
        lastError = null

        if (!HevTunnelManager.isLibLoaded()) {
            lastError = "hev-socks5-tunnel 라이브러리 미탑재"
            stopSelf(); return
        }

        val s = SettingsManager(this)
        val host = s.srvHost; val port = s.srvPort
        val user = s.srvUser; val pass = s.srvPass
        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            lastError = "서버 정보가 비어 있습니다"; stopSelf(); return
        }

        val underlying = LocalHotspotManager.hotspotNetwork
        if (underlying == null) {
            lastError = "핫스팟 네트워크 미준비"
            stopSelf(); return
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification("VPN 연결 중"))
            val builder = Builder()
            builder.setSession("MyInternet")
            
            // 1. VPN 가상 IP 및 기본 라우팅
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0)
            builder.addAddress("fd00::1", 128)
            
            // 2. 핵심: SOCKS 서버(10.204.228.225)로 가는 트래픽은 VPN을 타지 않도록 우회!
            try {
                val serverIp = java.net.InetAddress.getByName(host).hostAddress
                if (serverIp != null && !serverIp.contains(":")) {
                    builder.addRoute(serverIp, 32)
                    Log.i("MyVpn", "Bypassed VPN for SOCKS server: $serverIp")
                }
            } catch (_: Exception) {}

            builder.setUnderlyingNetworks(arrayOf(underlying))
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(1350)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            val pfd = builder.establish() ?: run { lastError = "TUN 생성 실패"; stopSelf(); return }
            tun = pfd

            val started = HevTunnelManager.start(pfd.fd, host, port, user, pass, cacheDir)
            if (!started) {
                lastError = HevTunnelManager.lastError ?: "hev 시작 실패"
                try { pfd.close() } catch (_: Exception) {}
                tun = null; isRunning = false
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(); return
            }
            
            isRunning = true
            Log.i("MyVpn", "VPN running; hev started; underlying=$underlying")
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
