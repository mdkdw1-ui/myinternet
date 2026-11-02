package com.myinternet.share

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File

class WireGuardService : android.app.Service() {
    private var backend: Backend? = null
    private var tunnel: Tunnel? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("wg", "onStartCommand action=${intent?.action} confPath=${intent?.getStringExtra("confPath")}")
        when (intent?.action) {
            "start" -> {
                val confPath = intent.getStringExtra("confPath")
                if (confPath.isNullOrEmpty()) {
                    AppLog.e("wg", "confPath 없음")
                    stopSelf(); return START_NOT_STICKY
                }
                if (VpnService.prepare(this) != null) {
                    AppLog.e("wg", "VPN 권한 없음 - prepare 필요")
                    stopSelf(); return START_NOT_STICKY
                }
                startWg(confPath)
            }
            "stop" -> { stopWg() }
            else -> { stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startWg(confPath: String) {
        AppLog.i("wg", "startWg 진입 confPath=$confPath")
        try {
            val f = File(confPath)
            if (!f.exists()) {
                AppLog.e("wg", "conf 파일 없음")
                stopSelf(); return
            }
            val cfg = f.inputStream().use { Config.parse(it) }

            backend = GoBackend(this)
            tunnel = object : Tunnel {
                override fun getName() = "myinternet-wg"
                override fun onStateChange(newState: Tunnel.State) {
                    AppLog.i("wg", "state=$newState")
                    running = newState == Tunnel.State.UP
                    started = running
                }
            }

            Thread {
                try {
                    backend?.setState(tunnel, Tunnel.State.UP, cfg)
                    running = true
                    started = true
                    AppLog.i("wg", "WireGuard UP")
                } catch (t: Throwable) {
                    AppLog.e("wg", "WireGuard UP 실패 (스레드)", t)
                    running = false
                    started = false
                    stopSelf()
                }
            }.start()
        } catch (t: Throwable) {
            AppLog.e("wg", "startWg 예외", t)
            running = false
            started = false
            stopSelf()
        }
    }

    private fun stopWg() {
        try {
            if (tunnel != null) backend?.setState(tunnel, Tunnel.State.DOWN, null)
            AppLog.i("wg", "WireGuard DOWN")
        } catch (t: Throwable) {
            AppLog.e("wg", "stopWg 예외", t)
        }
        running = false
        started = false
        stopSelf()
    }

    override fun onDestroy() {
        stopWg()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
    }
}
