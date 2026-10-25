package com.mdkdw1.myinternet

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
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

        fun protectSocket(s: java.net.Socket): Boolean {
            val result = try { instance?.protect(s) ?: false } catch (e: Exception) {
                Log.e("MyVpn", "protectSocket(Socket) failed", e)
                false
            }
            Log.d("MyVpn", "protectSocket(Socket) result=$result")
            return result
        }

        fun protectSocket(s: java.net.DatagramSocket): Boolean {
            val result = try { instance?.protect(s) ?: false } catch (e: Exception) {
                Log.e("MyVpn", "protectSocket(DatagramSocket) failed", e)
                false
            }
            Log.d("MyVpn", "protectSocket(DatagramSocket) result=$result")
            return result
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var tun2socks: Tun2Socks? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i("MyVpn", "onCreate")
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "MyInternet VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
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
        Log.i("MyVpn", "startVpn() begin")
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
        if (isRunning) {
            Log.i("MyVpn", "startVpnInternal() already running")
            return
        }
        lastError = null

        val s = SettingsManager(this)
        val host = s.srvHost.trim()
        val port = s.srvPort.coerceIn(1, 65535)
        val user = s.srvUser.trim()
        val pass = s.srvPass.trim()

        Log.i("MyVpn", "vpn-precheck host=$host port=$port userSet=${user.isNotEmpty()} passSet=${pass.isNotEmpty()}")

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
            java.net.InetAddress.getByName(host).hostAddress
        } catch (e: Exception) {
            Log.e("MyVpn", "resolve host failed: $host", e)
            lastError = "서버 주소 해석 실패: $host"
            stopSelf(); return
        }
        Log.i("MyVpn", "vpn-precheck resolvedHost=$resolvedHost")

        try {
            val builder = Builder()
            builder.setSession("MyInternet")
            builder.addAddress("10.0.0.2", 32)
            builder.addAddress("fd00::1", 128)

            // 전체 0.0.0.0/0 라우트는 루프/오동작을 유발할 수 있어 최소화.
            builder.addRoute("0.0.0.0", 0)

            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            builder.setUnderlyingNetworks(arrayOf(underlying))
            builder.addDnsServer("1.1.1.1")
            builder.setMtu(1500)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

            Log.i("MyVpn", "establish() start with underlying=$underlying")
            val pfd = builder.establish() ?: run {
                lastError = "TUN 생성 실패"
                Log.e("MyVpn", "builder.establish() returned null")
                stopSelf(); return
            }
            tun = pfd
            Log.i("MyVpn", "TUN established fd=${pfd.fileDescriptor}")

            Log.i("MyVpn", "Tun2Socks 시작: host=$host, port=$port")
            val inStream = FileInputStream(pfd.fileDescriptor)
            val outStream = FileOutputStream(pfd.fileDescriptor)
            tun2socks = Tun2Socks(inStream, outStream, host, port, user, pass)
            tun2socks?.start()
            Log.i("MyVpn", "Tun2Socks 시작 완료")

            isRunning = true
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification("VPN 연결됨 · $host:$port"))
            Log.i("MyVpn", "VPN running; host=$host port=$port underlying=$underlying")

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
        tun2socks?.stop(); tun2socks = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null; isRunning = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        Log.i("MyVpn", "onDestroy")
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

