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
            lastError = "라이브러리 로드 실패: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "loadLibrary failed", e)
        }
    }

    fun isLibLoaded(): Boolean = libLoaded

    fun start(tunFd: Int, srvHost: String, srvPort: Int, user: String, pass: String, cacheDir: File): Boolean {
        if (!libLoaded) { lastError = lastError ?: "libhev-socks5-tunnel 미로드"; return false }
        if (running) return true
        lastError = null
        return try {
            val configFile = File(cacheDir, "hev_config.yml")
            val yaml = """
                tunnel:
                  mtu: 1350
                socks5:
                  proxy:
                    address: $srvHost
                    port: $srvPort
                    udp: 'udp'
                  username: $user
                  password: $pass
            """.trimIndent()
            configFile.writeText(yaml)
            Log.i(TAG, "TProxyStartService cfg=${configFile.absolutePath} fd=$tunFd host=$srvHost:$srvPort")

            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            running = true
            true
        } catch (e: Throwable) {
            lastError = "JNI 호출 실패: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "TProxyStartService failed", e)
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
