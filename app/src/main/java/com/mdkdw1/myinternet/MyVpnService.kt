package com.mdkdw1.myinternet

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.InetAddress

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
        Log.i("MyVpn", "startVpn() entered")
        startVpnForeground("VPN 연결 준비 중...")
        try { startVpnInternal() }
        catch (e: Throwable) {
            lastError = "VPN 시작 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e("MyVpn", "startVpn error", e)
            try { stopVpn() } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun startVpnInternal() {
        if (isRunning) {
            Log.i("MyVpn", "startVpnInternal() already running")
            return
        }
        lastError = null

        // hev 라이브러리 로드 확인
        val libLoaded = try { HevTunnelManager.isLibLoaded() } catch (e: Throwable) {
            lastError = "HevTunnelManager 초기화 실패: ${e.javaClass.simpleName} - ${e.message}"
            Log.e("MyVpn", "HevTunnelManager access failed", e)
            false
        }
        Log.i("MyVpn", "hev libLoaded=$libLoaded, hevLastError=${HevTunnelManager.lastError}")
        if (!libLoaded) {
            lastError = lastError ?: HevTunnelManager.lastError ?: "hev 라이브러리 미탑재"
            Log.e("MyVpn", "vpn-precheck failed: lib not loaded ($lastError)")
            stopSelf(); return
        }

        val s = SettingsManager(this)
        val host = s.srvHost.trim()
        val port = s.srvPort.coerceIn(1, 65535)
        val user = s.srvUser.trim()
        val pass = s.srvPass.trim()
        Log.i("MyVpn", "vpn-precheck host=$host port=$port userEmpty=${user.isEmpty()} passEmpty=${pass.isEmpty()}")

        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            lastError = "서버 정보가 없습니다"
            Log.e("MyVpn", "vpn-precheck failed: blank host/user/pass")
            stopSelf(); return
        }

        val underlying = LocalHotspotManager.hotspotNetwork
        if (underlying == null) {
            lastError = "핫스팟 네트워크 미준비"
            Log.e("MyVpn", "vpn-precheck failed: hotspotNetwork is null")
            stopSelf(); return
        }

        val resolvedHost = try {
            InetAddress.getByName(host).hostAddress
        } catch (e: Exception) {
            Log.e("MyVpn", "resolve host failed: $host", e)
            lastError = "서버 주소 해석 실패: $host"
            stopSelf(); return
        }
        Log.i("MyVpn", "vpn-precheck resolvedHost=$resolvedHost underlying=$underlying")

        try {
            val builder = Builder()
            builder.setSession("MyInternet")
            builder.addAddress("10.0.0.2", 32)
            builder.addAddress("fd00::1", 128)
            builder.addRoute("0.0.0.0", 0)

            // 서버 IP 우회 (VPN 루프 방지)
            try {
                if (resolvedHost != null && !resolvedHost.contains(":")) {
                    builder.addRoute(resolvedHost, 32)
                    Log.i("MyVpn", "bypass route added: $resolvedHost/32")
                }
            } catch (_: Exception) {}

            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            builder.setUnderlyingNetworks(arrayOf(underlying))
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(1280)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            Log.i("MyVpn", "establish() start")
            val pfd = builder.establish()
            if (pfd == null) {
                lastError = "TUN 생성 실패 (VPN 권한 거부 또는 다른 VPN 사용 중)"
                Log.e("MyVpn", "builder.establish() returned null")
                try { stopVpn() } catch (_: Exception) {}
                stopSelf(); return
            }
            tun = pfd
            Log.i("MyVpn", "TUN established fd=${pfd.fd}")

            Log.i("MyVpn", "HevTunnelManager.start() start")
            val started = HevTunnelManager.start(pfd.fd, host, port, user, pass, cacheDir)
            if (!started) {
                lastError = HevTunnelManager.lastError ?: "hev 시작 실패"
                Log.e("MyVpn", "Hev start failed: $lastError")
                try { pfd.close() } catch (_: Exception) {}
                tun = null; isRunning = false
                try { stopVpn() } catch (_: Exception) {}
                stopSelf(); return
            }

            isRunning = true
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification("VPN 연결됨 · $host:$port"))
            Log.i("MyVpn", "VPN running; hev started; host=$host port=$port underlying=$underlying")
        } catch (e: Exception) {
            lastError = "VPN 시작 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e("MyVpn", "startVpnInternal failed", e)
            try { tun?.close() } catch (_: Exception) {}
            tun = null; isRunning = false
            stopSelf()
        }
    }

    private fun stopVpn() {
        Log.i("MyVpn", "stopVpn() called")
        HevTunnelManager.stop()
        try { tun?.close() } catch (_: Exception) {}
        tun = null; isRunning = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        Log.i("MyVpn", "onDestroy()")
        stopVpn(); instance = null; super.onDestroy()
    }

    private fun startVpnForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
