package com.mdkdw1.myinternet

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

class MyVpnService : VpnService() {
    companion object {
        const val CHANNEL_ID = "vpn_service"
        const val NOTIFICATION_ID = 1002
        const val ACTION_START = "VPN_START"
        const val ACTION_STOP = "VPN_STOP"

        @Volatile var isRunning = false
        @Volatile var lastError: String? = null
        @Volatile var instance: MyVpnService? = null

        fun protectSocket(s: java.net.Socket) { try { instance?.protect(s) } catch (_: Exception) {} }
        fun protectSocket(s: java.net.DatagramSocket) { try { instance?.protect(s) } catch (_: Exception) {} }
    }

    private var tun: ParcelFileDescriptor? = null
    private var tun2socks: Tun2Socks? = null

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
        if (isRunning) return
        lastError = null
        val s = SettingsManager(this)
        val host = s.srvHost; val port = s.srvPort
        val user = s.srvUser; val pass = s.srvPass
        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            lastError = "서버 정보가 비어 있습니다"; stopSelf(); return
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification("VPN 연결 중"))
            val builder = Builder()
            builder.setSession("MyInternet")
            builder.addAddress("10.0.0.2", 32)
            // UDP 패킷이 VPN을 완벽히 통과하도록 0.0.0.0 라우팅
            builder.addRoute("0.0.0.0", 0)
            // IPv6 라우팅 차단 (게임 UDP 지연 방지)
            builder.addAddress("fd00::1", 128)
            
            LocalHotspotManager.hotspotNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            
            // 게임 서버 DNS 응답을 위해 여러 DNS 등록
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(1350)
            
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            val pfd = builder.establish() ?: run { lastError = "TUN 생성 실패"; stopSelf(); return }
            tun = pfd
            
            val inStream = FileInputStream(pfd.fileDescriptor)
            val outStream = FileOutputStream(pfd.fileDescriptor)
            tun2socks = Tun2Socks(inStream, outStream, host, port, user, pass)
            tun2socks?.start()
            isRunning = true
        } catch (e: Exception) {
            lastError = e.message
            try { tun?.close() } catch (_: Exception) {}
            tun = null; isRunning = false
            stopSelf()
        }
    }

    private fun stopVpn() {
        tun2socks?.stop(); tun2socks = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null; isRunning = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        stopVpn(); instance = null; super.onDestroy()
    }

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
