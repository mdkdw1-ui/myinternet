package com.myinternet.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File
import java.util.concurrent.Executors

class WireGuardService : Service() {
    private var backend: Backend? = null
    private var tunnel: Tunnel? = null
    @Volatile private var started = false
    @Volatile private var starting = false
    @Volatile private var stopping = false
    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "start" -> {
                val confPath = intent.getStringExtra("confPath")
                if (confPath.isNullOrEmpty() || VpnService.prepare(this) != null) {
                    stopSelf(startId); return START_NOT_STICKY
                }
                promoteForeground()
                startWg(confPath, startId)
            }
            "stop" -> stopWg(startId)
            else -> { if (!running) stopSelf(startId) }
        }
        return START_STICKY
    }

    private fun promoteForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel("wg_channel", "WireGuard 상태", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val n = Notification.Builder(this, "wg_channel")
                .setContentTitle("MyInternet WireGuard 실행 중")
                .setContentText("외부 IP 우회 터널 유지 중")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(3, n)
            }
            AppLog.i("wg", "promoteForeground 완료")
        } catch (t: Throwable) {
            AppLog.e("wg", "promoteForeground 실패", t)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (running) {
            val f = File(filesDir, "wg.conf")
            if (f.exists()) {
                val restartIntent = Intent(applicationContext, WireGuardService::class.java).apply {
                    action = "start"; putExtra("confPath", f.absolutePath)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restartIntent)
                else startService(restartIntent)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startWg(confPath: String, startId: Int) {
        if (started || starting || stopping) return
        starting = true
        executor.execute {
            try {
                val f = File(confPath)
                if (!f.exists()) { starting = false; stopSelf(startId); return@execute }
                val cfg = f.inputStream().use { Config.parse(it) }
                val newBackend = GoBackend(this)
                val newTunnel = object : Tunnel {
                    override fun getName() = "myinternet-wg"
                    override fun onStateChange(newState: Tunnel.State) {
                        running = newState == Tunnel.State.UP; started = running
                    }
                }
                backend = newBackend; tunnel = newTunnel
                newBackend.setState(newTunnel, Tunnel.State.UP, cfg)
                running = true; started = true; starting = false
            } catch (t: Throwable) {
                running = false; started = false; starting = false; backend = null; tunnel = null
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
                stopSelf(startId)
            }
        }
    }

    private fun stopWg(startId: Int) {
        if (stopping) return
        stopping = true
        executor.execute {
            try {
                val b = backend; val t = tunnel
                if (b != null && t != null) b.setState(t, Tunnel.State.DOWN, null)
            } catch (_: Throwable) {} finally {
                running = false; started = false; starting = false; stopping = false
                backend = null; tunnel = null
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
                stopSelf(startId)
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        running = false; started = false; starting = false; stopping = false
        super.onDestroy()
    }

    companion object { @Volatile var running = false }
}
