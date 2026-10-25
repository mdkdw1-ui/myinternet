package com.myinternet.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ServerService : Service() {
    private var server: Socks5Server? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("srv", "Server", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "srv")
            .setContentTitle("MyInternet 서버 실행 중")
            .setContentText("SOCKS5 포트 $PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
        if (server == null) {
            try {
                server = Socks5Server(PORT).also { it.start() }
            } catch (e: Exception) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        const val PORT = 1080
    }
}
