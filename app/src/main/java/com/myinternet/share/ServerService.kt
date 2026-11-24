package com.myinternet.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class ServerService : Service() {
    private var server: Socks5Server? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopServerInternal()
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        AppLog.i("srv", "ServerService onStartCommand")
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("srv", "Server", NotificationManager.IMPORTANCE_LOW)
            )
            val n = Notification.Builder(this, "srv")
                .setContentTitle("MyInternet 서버 실행 중")
                .setContentText("SOCKS5 포트 $PORT (백그라운드 유지)")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, n)
            }
            if (wl == null) {
                wl = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myinternet:server")
                    .apply { setReferenceCounted(false); acquire() }
            }
            if (server == null) {
                server = Socks5Server(PORT).also { it.start() }
                running = true
                serverInstance = server
            }
        } catch (t: Throwable) {
            AppLog.e("srv", "서버 시작 실패", t)
            running = false
            server = null
            stopSelf()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i("srv", "onTaskRemoved - 서버 백그라운드 유지")
        if (running) {
            val restartIntent = Intent(applicationContext, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopServerInternal() {
        server?.stop()
        server = null
        serverInstance = null
        running = false
        try { wl?.release() } catch (_: Exception) {}
        wl = null
    }

    override fun onDestroy() {
        AppLog.i("srv", "ServerService onDestroy")
        stopServerInternal()
        super.onDestroy()
    }

    companion object {
        const val PORT = 1080
        @Volatile var running = false
        @Volatile var serverInstance: Socks5Server? = null
        val activeClientCount: Int get() = serverInstance?.activeConnections?.get() ?: 0
    }
}
