package com.mdkdw1.myinternet

import android.util.Log
import java.io.File

object HevTunnelManager {
    private const val TAG = "HevTunnel"
    @Volatile private var running = false
    @Volatile var lastError: String? = null
    @Volatile private var libLoaded = false

    init {
        Log.i(TAG, "init: loadLibrary hev-socks5-tunnel")
        try {
            System.loadLibrary("hev-socks5-tunnel")
            libLoaded = true
            Log.i(TAG, "libhev-socks5-tunnel loaded successfully")
        } catch (e: Throwable) {
            lastError = "라이브러리 로드 실패: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "loadLibrary failed", e)
        }
    }

    fun isLibLoaded(): Boolean = libLoaded

    fun start(tunFd: Int, srvHost: String, srvPort: Int, user: String, pass: String, cacheDir: File): Boolean {
        Log.i(TAG, "start() tunFd=$tunFd srv=$srvHost:$srvPort user=${user.isNotEmpty()} cacheDir=$cacheDir")
        if (!libLoaded) { lastError = "라이브러리 미탑재"; Log.e(TAG, "lib not loaded"); return false }
        if (running) { Log.i(TAG, "already running"); return true }
        lastError = null

        return try {
            val configFile = File(cacheDir, "hev_config.yml")
            val yaml = "tunnel:\n" +
                       "  mtu: 8500\n" +
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
                       "  connect-timeout: 10000\n" +
                       "  tcp-read-write-timeout: 300000\n" +
                       "  udp-read-write-timeout: 60000\n"
            configFile.writeText(yaml)
            Log.i(TAG, "config written (${configFile.length()} bytes):\n$yaml")

            Log.i(TAG, "calling TProxyStartService(path=${configFile.absolutePath}, fd=$tunFd)")
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            running = true
            Log.i(TAG, "TProxyStartService returned OK; running=true")
            configFile.delete()
            true
        } catch (e: Throwable) {
            lastError = "Hev 시작 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "EXCEPTION in start()", e)
            false
        }
    }

    fun stop() {
        Log.i(TAG, "stop() running=$running")
        if (!running) return
        try { hev.htproxy.TProxyService.TProxyStopService() } catch (e: Exception) {
            Log.e(TAG, "TProxyStopService exception", e)
        }
        running = false
    }

    fun isRunning(): Boolean = running
}
