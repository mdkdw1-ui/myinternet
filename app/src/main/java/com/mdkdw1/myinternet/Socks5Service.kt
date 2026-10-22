package com.mdkdw1.myinternet

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder

class Socks5Service : Service() {
    companion object {
        const val CHANNEL_ID = "socks5_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"

        @Volatile var isRunning = false
        @Volatile var server: Socks5Server? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "MyInternet", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> { stopServer(); stopSelf() }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (isRunning) return
        val s = SettingsManager(this)
        startForeground(NOTIFICATION_ID, buildNotification("SOCKS5 서버 실행 중 · 포트 ${s.port}"))
        server = Socks5Server(s.port, s.username, s.password).also { it.start() }
        isRunning = true
    }

    private fun stopServer() {
        server?.stop()
        server = null
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, Socks5Service::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        b.setContentTitle("MyInternet").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.addAction(Notification.Action.Builder(null, "중지", stopPending).build())
        } else {
            @Suppress("DEPRECATION") b.addAction(android.R.drawable.ic_delete, "중지", stopPending)
        }
        return b.build()
    }
}
