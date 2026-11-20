package com.myinternet.share

import android.content.Intent
import android.app.Service
import android.net.VpnService
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

    // UP/DOWN 작업을 순서대로 처리
    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("wg", "onStartCommand action=${intent?.action} confPath=${intent?.getStringExtra("confPath")}")

        when (intent?.action) {
            "start" -> {
                val confPath = intent.getStringExtra("confPath")

                if (confPath.isNullOrEmpty()) {
                    AppLog.e("wg", "confPath 없음")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                if (VpnService.prepare(this) != null) {
                    AppLog.e("wg", "VPN 권한 없음")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                startWg(confPath, startId)
            }

            "stop" -> stopWg(startId)

            else -> stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun startWg(confPath: String, startId: Int) {

        if (started || starting || stopping) {
            AppLog.i("wg", "이미 실행 중이거나 작업 중 - 시작 요청 무시")
            return
        }

        starting = true

        executor.execute {
            AppLog.i("wg", "백그라운드 WireGuard 시작")

            try {
                val f = File(confPath)

                if (!f.exists()) {
                    AppLog.e("wg", "conf 파일 없음")
                    starting = false
                    stopSelf(startId)
                    return@execute
                }

                val cfg = f.inputStream().use { Config.parse(it) }

                val newBackend = GoBackend(this)

                val newTunnel = object : Tunnel {
                    override fun getName() = "myinternet-wg"

                    override fun onStateChange(newState: Tunnel.State) {
                        AppLog.i("wg", "state=$newState")
                        running = newState == Tunnel.State.UP
                        started = running
                    }
                }

                backend = newBackend
                tunnel = newTunnel

                newBackend.setState(newTunnel, Tunnel.State.UP, cfg)

                running = true
                started = true
                starting = false

                AppLog.i("wg", "WireGuard UP")

            } catch (t: Throwable) {
                AppLog.e("wg", "WireGuard UP 실패", t)

                running = false
                started = false
                starting = false

                backend = null
                tunnel = null

                stopSelf(startId)
            }
        }
    }

    private fun stopWg(startId: Int) {

        if (stopping) {
            AppLog.i("wg", "이미 종료 작업 중 - 중복 요청 무시")
            return
        }

        stopping = true

        executor.execute {
            AppLog.i("wg", "백그라운드 WireGuard 종료")

            try {
                val b = backend
                val t = tunnel

                if (b != null && t != null) {
                    b.setState(t, Tunnel.State.DOWN, null)
                }

                AppLog.i("wg", "WireGuard DOWN")

            } catch (e: Throwable) {
                AppLog.e("wg", "WireGuard DOWN 오류", e)

            } finally {
                running = false
                started = false
                starting = false
                stopping = false

                backend = null
                tunnel = null

                stopSelf(startId)
            }
        }
    }

    override fun onDestroy() {
        AppLog.i("wg", "WireGuardService onDestroy")

        // stopWg() 중복 호출하지 않음
        executor.shutdown()

        running = false
        started = false
        starting = false
        stopping = false

        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
    }
}
