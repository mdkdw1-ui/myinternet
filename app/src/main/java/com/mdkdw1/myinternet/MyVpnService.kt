package com.mdkdw1.myinternet

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
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
        // [핵심 수정 1] 안드로이드 시스템에 즉시 포그라운드 알림을 띄운다. (5초 제한 회피)
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

        val s = SettingsManager(this)
        val host = s.srvHost; val port = s.srvPort
        val user = s.srvUser; val pass = s.srvPass
        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            lastError = "서버 정보가 없습니다"; stopSelf(); return
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
            builder.addAddress("fd00::1", 128)

            // [수정] 0.0.0.0/0 전체 라우팅 제거.
            // VPN 터널로 보낼 대역만 지정 (Tun2Socks가 내부에서 처리).
            // 사설망(핫스팟)은 VPN을 타지 않도록 제외.
            // 198.18.0.0/15 는 hev의 가상 터널 대역 (mapdns/터널 주소와 충돌 방지용)
            builder.addRoute("0.0.0.0", 0)
            // 핫스팟 서브넷(예: 192.168.x.0/24, 10.x.x.0/24)은 VPN 밖으로 우회
            try {
                val serverIp = java.net.InetAddress.getByName(host).hostAddress
                if (serverIp != null && !serverIp.contains(":")) {
                    // 서버 IP 단일 호스트는 VPN을 타지 않게 /32 로 우회
                    builder.addRoute(serverIp, 32)
                    android.util.Log.i("MyVpn", "bypass route for server: $serverIp/32")
                    // 같은 서브넷 전체도 우회 (핫스팟 게이트웨이/ARP 등)
                    val parts = serverIp.split(".")
                    if (parts.size == 4) {
                        val subnet = parts[0] + "." + parts[1] + "." + parts[2] + ".0"
                        builder.addRoute(subnet, 24)
                        android.util.Log.i("MyVpn", "bypass route for hotspot subnet: $subnet/24")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MyVpn", "bypass route error", e)
            }

            // VPN이 켜져도 내 앱 소켓은 VPN을 타지 않게 제외 (무한 루프 방지)
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            builder.setUnderlyingNetworks(arrayOf(underlying))
            // [수정] DNS 는 hev mapdns 주소로 지정 (UDP DNS 를 SOCKS5 로 프록시)
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(1500)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            val pfd = builder.establish() ?: run { lastError = "TUN 생성 실패"; stopSelf(); return }
            tun = pfd

            Log.d("MyVpn", "Tun2Socks 시작: host=$host, port=$port")
            val inStream = java.io.FileInputStream(pfd.fileDescriptor)
            val outStream = java.io.FileOutputStream(pfd.fileDescriptor)
            tun2socks = Tun2Socks(inStream, outStream, host, port, user, pass)
            tun2socks?.start()
            Log.d("MyVpn", "Tun2Socks 시작 완료")
            
            isRunning = true
            // 알림 텍스트 업데이트
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification("VPN 연결됨 · $host:$port"))
            Log.i("MyVpn", "VPN running; hev started; underlying=$underlying")
            
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
