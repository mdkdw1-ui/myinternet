package com.mdkdw1.myinternet

import android.util.Log
import java.io.File

object HevTunnelManager {
    private const val TAG = "HevTunnel"
    @Volatile private var running = false
    @Volatile var lastError: String? = null
    @Volatile private var libLoaded = false

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            libLoaded = true
            Log.i(TAG, "libhev-socks5-tunnel loaded")
        } catch (e: Throwable) {
            lastError = "라이브러리 로드 실패: ${e.message}"
            Log.e(TAG, "loadLibrary failed", e)
        }
    }

    fun isLibLoaded(): Boolean = libLoaded

    fun start(tunFd: Int, srvHost: String, srvPort: Int, user: String, pass: String, cacheDir: File): Boolean {
        if (!libLoaded) { lastError = "라이브러리 미탑재"; return false }
        if (running) return true
        lastError = null

        return try {
            val configFile = File(cacheDir, "hev_config.yml")
            // 공식 conf/main.yml 스키마
            val yaml = "tunnel:\n" +
                       "  mtu: 1500\n" +
                       "  ipv4: 198.18.0.1\n" +
                       "  ipv6: 'fc00::1'\n" +
                       "socks5:\n" +
                       "  port: $srvPort\n" +
                       "  address: $srvHost\n" +
                       "  udp: 'udp'\n" +
                       "  username: $user\n" +
                       "  password: $pass\n" +
                       "misc:\n" +
                       "  log-level: debug\n" +
                       "  task-stack-size: 86016\n" +
                       "  connect-timeout: 10000\n"
            configFile.writeText(yaml)
            Log.i(TAG, "config written (${configFile.length()} bytes): $yaml")

            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            running = true
            Log.i(TAG, "TProxyStartService returned; running=true")
            configFile.delete()
            true
        } catch (e: Throwable) {
            lastError = "Hev 시작 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "EXCEPTION", e)
            false
        }
    }

    fun stop() {
        if (!running) return
        try { hev.htproxy.TProxyService.TProxyStopService() } catch (_: Exception) {}
        running = false
    }

    fun isRunning(): Boolean = running
}
