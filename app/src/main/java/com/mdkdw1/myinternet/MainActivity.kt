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

        fun protectSocket(s: java.net.Socket): Boolean { return try { instance?.protect(s) ?: false } catch (_: Exception) { false } }
        fun protectSocket(s: java.net.DatagramSocket): Boolean { return try { instance?.protect(s) ?: false } catch (_: Exception) { false } }
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
        Log.i("MyVpn", "service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("MyVpn", "onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> { stopVpn(); stopSelf() }
        }
        return START_STICKY
    }

    private fun startVpn() {
        startForeground(NOTIFICATION_ID, buildNotification("VPN 연결 준비 중..."))
        Log.i("MyVpn", "startVpn() called")
        try {
            startVpnInternal()
        } catch (e: Throwable) {
            lastError = "VPN 시작 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e("MyVpn", "startVpn error", e)
            try { stopVpn() } catch (_: Exception) {}
            // 실패 시 서비스 종료 대신 상태만 남긴다. 서비스가 죽으면 앱이 종료되는 것처럼 보인다.
        }
    }

    private fun startVpnInternal() {
        Log.i("MyVpn", "startVpnInternal() begin isRunning=$isRunning")
        if (isRunning) {
            Log.i("MyVpn", "already running, return")
            return
        }
        lastError = null

        val s = SettingsManager(this)
        val host = s.srvHost.trim(); val port = s.srvPort
        val user = s.srvUser.trim(); val pass = s.srvPass.trim()
        Log.i("MyVpn", "settings host=$host port=$port user=${user.ifBlank { "<blank>" }} pass=${pass.ifBlank { "<blank>" }}")

        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            lastError = "서버 정보가 없습니다"
            Log.w("MyVpn", "missing config; host=$host userBlank=${user.isBlank()} passBlank=${pass.isBlank()}")
            return
        }

        val underlying = LocalHotspotManager.hotspotNetwork
        if (underlying == null) {
            lastError = "핫스팟 네트워크 미준비"
            Log.w("MyVpn", "underlying hotspot network is null")
            return
        }
        Log.i("MyVpn", "underlying network: $underlying")

        try {
            val builder = Builder()
            builder.setSession("MyInternet")
            builder.addAddress("10.0.0.2", 32)
            builder.addAddress("fd00::1", 128)

            // 전체 라우트는 루프를 만들 수 있어 사용하지 않는다.
            // builder.addRoute("0.0.0.0", 0)

            // 로컬/핫스팟 대역은 VPN 밖으로 우회
            val localRoutes = listOf(
                "10.0.0.0" to 8,
                "172.16.0.0" to 12,
                "192.168.0.0" to 16,
                "169.254.0.0" to 16
            )
            for ((cidr, prefix) in localRoutes) {
                try {
                    builder.addRoute(cidr, prefix)
                    Log.i("MyVpn", "added local route: $cidr/$prefix")
                } catch (e: Exception) {
                    Log.w("MyVpn", "failed to add route $cidr/$prefix", e)
                }
            }

            try {
                val serverIp = java.net.InetAddress.getByName(host).hostAddress
                if (!serverIp.isNullOrBlank() && !serverIp.contains(":")) {
                    builder.addRoute(serverIp, 32)
                    Log.i("MyVpn", "added bypass route to server: $serverIp/32")
                }
            } catch (e: Exception) {
                Log.w("MyVpn", "server route bypass failed: $host", e)
            }

            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            builder.setUnderlyingNetworks(arrayOf(underlying))
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(1500)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            Log.i("MyVpn", "builder.establish() start")
            val pfd = builder.establish()
            if (pfd == null) {
                lastError = "TUN 생성 실패"
                Log.e("MyVpn", "builder.establish() returned null")
                return
            }
            tun = pfd

            Log.d("MyVpn", "Tun2Socks 시작: host=$host, port=$port")
            val inStream = java.io.FileInputStream(pfd.fileDescriptor)
            val outStream = java.io.FileOutputStream(pfd.fileDescriptor)
            tun2socks = Tun2Socks(inStream, outStream, host, port, user, pass)
            tun2socks?.start()
            Log.d("MyVpn", "Tun2Socks 시작 완료")

            isRunning = true
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification("VPN 연결됨 · $host:$port"))
            Log.i("MyVpn", "VPN running; underlying=$underlying")

        } catch (e: Exception) {
            lastError = e.message ?: "VPN 설정 실패"
            Log.e("MyVpn", "startVpnInternal failed", e)
            try { tun?.close() } catch (_: Exception) {}
            tun = null
            isRunning = false
        }
    }

    private fun stopVpn() {
        Log.i("MyVpn", "stopVpn() called")
        tun2socks?.stop(); tun2socks = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null; isRunning = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        Log.i("MyVpn", "service onDestroy")
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
