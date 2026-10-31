package com.myinternet.share

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File

class WireGuardService : Service() {
    private var backend: Backend? = null
    private var tunnel: Tunnel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("wg", "onStartCommand 진입 action=${intent?.action} confPath=${intent?.getStringExtra("confPath")}")
        when (intent?.action) {
            "start" -> {
                val confPath = intent.getStringExtra("confPath")
                if (confPath.isNullOrEmpty()) {
                    AppLog.e("wg", "confPath 없음")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startWg(confPath)
            }
            "stop" -> stopWg()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startWg(confPath: String) {
        AppLog.i("wg", "startWg 진입 confPath=$confPath")
        try {
            val f = File(confPath)
            if (!f.exists()) {
                AppLog.e("wg", "conf 파일 없음: $confPath")
                stopSelf()
                return
            }
            val cfg = f.inputStream().use { Config.parse(it) }
            backend = GoBackend(this)
            tunnel = object : Tunnel {
                override fun getName() = "myinternet-wg"
                override fun onStateChange(newState: Tunnel.State) {
                    AppLog.i("wg", "state=$newState")
                }
            }
            backend?.setState(tunnel, Tunnel.State.UP, cfg)
            running = true
            AppLog.i("wg", "WireGuard UP")
        } catch (t: Throwable) {
            AppLog.e("wg", "WireGuard 시작 실패", t)
            running = false
            stopSelf()
        }
    }

    private fun stopWg() {
        try {
            backend?.setState(tunnel, Tunnel.State.DOWN, null)
            AppLog.i("wg", "WireGuard DOWN")
        } catch (t: Throwable) {
            AppLog.e("wg", "WireGuard 중지 실패", t)
        }
        running = false
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
