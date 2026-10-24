package com.mdkdw1.myinternet

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

class MyVpnService : VpnService() {
    companion object {
        const val CHANNEL_ID = "vpn_service"
        const val NOTIFICATION_ID = 1002
        const val ACTION_START = "VPN_START"
        const val ACTION_STOP = "VPN_STOP"
        private const val TAG = "MyVpn"

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
            Log.e(TAG, "Start error", e)
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

        // [추가] VPN을 켜기 전에 서버(핫스팟 망)로 소켓이 닿는지 먼저 테스트
        if (!testServerConnection(host, port)) {
            lastError = "SOCKS 서버 접속 불가 ($host:$port) - 서버 앱이 켜져 있는지 확인하세요"
            stopSelf(); return
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
            
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("0.0.0.0", 0)
            
            // [핵심 수정] addRoute(serverIp, 32) 대신, 우리 앱(hev) 자체를 VPN 터널에서 제외시킴!
            // 이렇게 하면 hev가 SOCKS 서버로 연결할 때 VPN을 타지 않고 핫스팟 와이파이로 직접 나갑니다.
            try {
                builder.addDisallowedApplication(packageName)
                Log.i(TAG, "VPN 우회 설정 완료: $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "addDisallowedApplication 실패: ${e.message}")
            }

            builder.setUnderlyingNetworks(arrayOf(underlying))
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(HevTunnelManager.MTU) // MTU 통일 (1350)
            
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            val pfd = builder.establish() ?: run { lastError = "TUN 생성 실패"; stopSelf(); return }
            tun = pfd

            // 로컬 바인딩을 위해 앱 프로세스를 핫스팟으로 바인딩
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.bindProcessToNetwork(underlying)

            val started = HevTunnelManager.start(pfd.fd, host, port, user, pass, cacheDir)
            if (!started) {
                lastError = HevTunnelManager.lastError ?: "hev 시작 실패"
                try { pfd.close() } catch (_: Exception) {}
                tun = null; isRunning = false
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(); return
            }
            isRunning = true
            Log.i(TAG, "VPN running; hev started; underlying=$underlying")
        } catch (e: Exception) {
            lastError = e.message
            try { tun?.close() } catch (_: Exception) {}
            tun = null; isRunning = false
            stopSelf()
        }
    }

    private fun testServerConnection(host: String, port: Int): Boolean {
        return try {
            val sock = Socket()
            val n = LocalHotspotManager.hotspotNetwork
            if (n != null) n.bindSocket(sock) else protect(sock)
            sock.connect(InetSocketAddress(host, port), 3000)
            sock.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "사전 연결 테스트 실패: ${e.message}")
            false
        }
    }

    private fun stopVpn() {
        val cm = getSystemService(ConnectivityManager::class.java)
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        HevTunnelManager.stop()
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
